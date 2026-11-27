package webapi

import (
	"archive/zip"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strings"
	"time"

	"github.com/chrissnell/graywolf/pkg/configstore"
	"github.com/chrissnell/graywolf/pkg/historydb"
	"github.com/chrissnell/graywolf/pkg/platform"
	"github.com/chrissnell/graywolf/pkg/webapi/dto"
	"github.com/chrissnell/graywolf/pkg/webtypes"
)

const (
	maxRestoreBytes   int64 = 256 << 20
	maxConfigDBBytes  int64 = 64 << 20
	maxHistoryDBBytes int64 = 256 << 20

	backupConfigName  = "graywolf.db"
	backupHistoryName = "graywolf-history.db"
)

type backupManifest struct {
	Schema    int      `json:"schema"`
	CreatedAt string   `json:"created_at"`
	Version   string   `json:"version"`
	Platform  string   `json:"platform"`
	Files     []string `json:"files"`
}

// @Summary  Export configuration and optional history database backup
// @Description Creates a hot SQLite snapshot of graywolf.db (and optionally
// @Description graywolf-history.db when include_history=true) and streams a
// @Description zip archive containing manifest.json and the database file(s).
// @Tags     storage
// @ID       exportStorageBackup
// @Param    include_history query bool false "Include graywolf-history.db"
// @Produce  application/zip
// @Success  200 {file} file
// @Failure  500 {object} webtypes.ErrorResponse
// @Failure  501 {object} webtypes.ErrorResponse
// @Security CookieAuth
// @Router   /storage/backup [get]
func (s *Server) exportStorageBackup(w http.ResponseWriter, r *http.Request) {
	if s.store == nil || s.configDBPath == "" || strings.HasPrefix(s.configDBPath, ":") {
		writeJSON(w, http.StatusNotImplemented, webtypes.ErrorResponse{
			Error: "database backup is unavailable for an in-memory store",
		})
		return
	}

	q := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("include_history")))
	includeHistory := q == "true" || q == "1"

	tmpDir, err := os.MkdirTemp("", "graywolf-backup-*")
	if err != nil {
		s.internalError(w, r, "backup_tmpdir", err)
		return
	}
	defer os.RemoveAll(tmpDir)

	configSnap := filepath.Join(tmpDir, backupConfigName)
	if err := s.store.SnapshotTo(configSnap); err != nil {
		s.internalError(w, r, "backup_config_snapshot", err)
		return
	}
	files := []string{backupConfigName}

	historySnap := filepath.Join(tmpDir, backupHistoryName)
	hasHistory := false
	if includeHistory && s.historyDBPath != "" && !strings.HasPrefix(s.historyDBPath, ":") {
		if info, statErr := os.Stat(s.historyDBPath); statErr == nil && !info.IsDir() {
			if err := historydb.SnapshotFile(s.historyDBPath, historySnap); err != nil {
				s.internalError(w, r, "backup_history_snapshot", err)
				return
			}
			hasHistory = true
			files = append(files, backupHistoryName)
		}
	}

	now := time.Now().UTC()
	zipName := fmt.Sprintf("graywolf-backup-%s.zip", now.Format("20060102-150405"))
	zipPath := filepath.Join(tmpDir, zipName)
	if err := writeBackupZip(zipPath, backupManifest{
		Schema:    1,
		CreatedAt: now.Format(time.RFC3339),
		Version:   s.version,
		Platform:  platform.Kind,
		Files:     files,
	}, configSnap, historySnap, hasHistory); err != nil {
		s.internalError(w, r, "backup_write_zip", err)
		return
	}

	zf, err := os.Open(zipPath)
	if err != nil {
		s.internalError(w, r, "backup_open_zip", err)
		return
	}
	defer zf.Close()

	stat, err := zf.Stat()
	if err != nil {
		s.internalError(w, r, "backup_stat_zip", err)
		return
	}

	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", mime.FormatMediaType("attachment", map[string]string{"filename": zipName}))
	w.Header().Set("Cache-Control", "no-store")
	http.ServeContent(w, r, zipName, stat.ModTime(), zf)
}

func writeBackupZip(zipPath string, manifest backupManifest, configSnap, historySnap string, hasHistory bool) error {
	out, err := os.Create(zipPath)
	if err != nil {
		return err
	}
	zw := zip.NewWriter(out)

	manifestBytes, err := json.MarshalIndent(manifest, "", "  ")
	if err != nil {
		_ = zw.Close()
		_ = out.Close()
		return err
	}
	mw, err := zw.Create("manifest.json")
	if err != nil {
		_ = zw.Close()
		_ = out.Close()
		return err
	}
	if _, err := mw.Write(manifestBytes); err != nil {
		_ = zw.Close()
		_ = out.Close()
		return err
	}

	if err := addFileToZip(zw, configSnap, backupConfigName); err != nil {
		_ = zw.Close()
		_ = out.Close()
		return err
	}
	if hasHistory {
		if err := addFileToZip(zw, historySnap, backupHistoryName); err != nil {
			_ = zw.Close()
			_ = out.Close()
			return err
		}
	}

	if err := zw.Close(); err != nil {
		_ = out.Close()
		return err
	}
	return out.Close()
}

func addFileToZip(zw *zip.Writer, srcPath, entryName string) error {
	f, err := os.Open(srcPath)
	if err != nil {
		return err
	}
	defer f.Close()

	info, err := f.Stat()
	if err != nil {
		return err
	}
	hdr, err := zip.FileInfoHeader(info)
	if err != nil {
		return err
	}
	hdr.Name = entryName
	hdr.Method = zip.Deflate

	w, err := zw.CreateHeader(hdr)
	if err != nil {
		return err
	}
	_, err = io.Copy(w, f)
	return err
}

// @Summary  Restore configuration and optional history database from backup
// @Description Accepts a Graywolf backup .zip archive or a raw graywolf.db
// @Description SQLite file, validates integrity and schema compatibility,
// @Description stages the database(s) for atomic replacement, and triggers
// @Description an in-process restart when wired.
// @Tags     storage
// @ID       restoreStorageBackup
// @Accept   multipart/form-data
// @Param    file formData file true "Backup .zip or graywolf.db file"
// @Produce  json
// @Success  200 {object} dto.StorageRestoreResponse
// @Failure  400 {object} webtypes.ErrorResponse
// @Failure  413 {object} webtypes.ErrorResponse
// @Failure  500 {object} webtypes.ErrorResponse
// @Failure  501 {object} webtypes.ErrorResponse
// @Security CookieAuth
// @Router   /storage/restore [post]
func (s *Server) restoreStorageBackup(w http.ResponseWriter, r *http.Request) {
	if s.configDBPath == "" || strings.HasPrefix(s.configDBPath, ":") {
		writeJSON(w, http.StatusNotImplemented, webtypes.ErrorResponse{
			Error: "database restore is unavailable for an in-memory store",
		})
		return
	}

	configDir := filepath.Dir(s.configDBPath)
	if err := os.MkdirAll(configDir, 0o755); err != nil {
		s.internalError(w, r, "restore_mkdir", err)
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, maxRestoreBytes)
	uploadPath, err := saveUploadedRestorePayload(r, configDir)
	if err != nil {
		var maxErr *http.MaxBytesError
		if errors.As(err, &maxErr) {
			writeJSON(w, http.StatusRequestEntityTooLarge, webtypes.ErrorResponse{
				Error: "backup file exceeds maximum allowed size (256 MiB)",
			})
			return
		}
		badRequest(w, err.Error())
		return
	}
	defer func() {
		if uploadPath != "" {
			_ = os.Remove(uploadPath)
		}
	}()

	var tmpConfig string
	var tmpHistory string
	defer func() {
		if tmpConfig != "" {
			_ = os.Remove(tmpConfig)
		}
		if tmpHistory != "" {
			_ = os.Remove(tmpHistory)
		}
	}()

	kind, err := sniffRestorePayload(uploadPath)
	if err != nil {
		badRequest(w, err.Error())
		return
	}

	switch kind {
	case "sqlite":
		// Reuse uploadPath directly on configDir so promotion stays same-FS.
		tmpConfig = uploadPath
		uploadPath = ""
	case "zip":
		historyDir := ""
		if s.historyDBPath != "" && !strings.HasPrefix(s.historyDBPath, ":") {
			historyDir = filepath.Dir(s.historyDBPath)
		}
		tmpConfig, tmpHistory, err = extractBackupZip(uploadPath, configDir, historyDir)
		if err != nil {
			badRequest(w, err.Error())
			return
		}
	default:
		badRequest(w, "uploaded file must be a Graywolf backup .zip or a graywolf.db SQLite file")
		return
	}

	if err := configstore.ValidateConfigDB(tmpConfig); err != nil {
		badRequest(w, fmt.Sprintf("invalid config database: %v", err))
		return
	}
	if tmpHistory != "" {
		if err := historydb.ValidateHistoryDB(tmpHistory); err != nil {
			badRequest(w, fmt.Sprintf("invalid history database: %v", err))
			return
		}
	}

	if err := stagePendingFile(tmpConfig, s.configDBPath+configstore.PendingRestoreSuffix); err != nil {
		s.internalError(w, r, "restore_stage_config", err)
		return
	}
	tmpConfig = ""

	historyRestored := false
	if tmpHistory != "" && s.historyDBPath != "" && !strings.HasPrefix(s.historyDBPath, ":") {
		if err := stagePendingFile(tmpHistory, s.historyDBPath+configstore.PendingRestoreSuffix); err != nil {
			_ = os.Remove(s.configDBPath + configstore.PendingRestoreSuffix)
			s.internalError(w, r, "restore_stage_history", err)
			return
		}
		tmpHistory = ""
		historyRestored = true
	}

	msg := "Backup validated and staged. Graywolf is restarting to load the restored database."
	if s.restartFn == nil {
		msg = "Backup validated and staged. Restart Graywolf to load the restored database."
	}
	writeJSON(w, http.StatusOK, dto.StorageRestoreResponse{
		Staged:          true,
		Restarting:      s.restartFn != nil,
		ConfigRestored:  true,
		HistoryRestored: historyRestored,
		Message:         msg,
	})
	if flusher, ok := w.(http.Flusher); ok {
		flusher.Flush()
	}
	if s.restartFn != nil {
		time.AfterFunc(150*time.Millisecond, s.restartFn)
	}
}

func saveUploadedRestorePayload(r *http.Request, tempDir string) (string, error) {
	var src io.Reader
	ct := r.Header.Get("Content-Type")
	if strings.Contains(strings.ToLower(ct), "multipart/form-data") {
		if err := r.ParseMultipartForm(32 << 20); err != nil {
			return "", err
		}
		if r.MultipartForm != nil {
			defer func() { _ = r.MultipartForm.RemoveAll() }()
		}
		f, _, err := r.FormFile("file")
		if err != nil {
			return "", fmt.Errorf("missing 'file' upload field: %w", err)
		}
		defer f.Close()
		src = f
	} else {
		src = r.Body
	}

	tmp, err := os.CreateTemp(tempDir, ".graywolf-upload-*")
	if err != nil {
		return "", err
	}
	tmpPath := tmp.Name()
	n, copyErr := io.Copy(tmp, src)
	closeErr := tmp.Close()
	if copyErr != nil {
		_ = os.Remove(tmpPath)
		return "", copyErr
	}
	if closeErr != nil {
		_ = os.Remove(tmpPath)
		return "", closeErr
	}
	if n == 0 {
		_ = os.Remove(tmpPath)
		return "", errors.New("uploaded file is empty")
	}
	return tmpPath, nil
}

func sniffRestorePayload(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()

	var hdr [16]byte
	n, err := io.ReadFull(f, hdr[:])
	if err != nil && !errors.Is(err, io.ErrUnexpectedEOF) {
		return "", fmt.Errorf("read upload header: %w", err)
	}
	if n == 16 && string(hdr[:]) == "SQLite format 3\x00" {
		return "sqlite", nil
	}
	if n >= 4 && hdr[0] == 'P' && hdr[1] == 'K' && hdr[2] == 0x03 && hdr[3] == 0x04 {
		return "zip", nil
	}
	return "", errors.New("uploaded file is not a valid Graywolf backup .zip or SQLite database")
}

func extractBackupZip(zipPath, configDir, historyDir string) (string, string, error) {
	zr, err := zip.OpenReader(zipPath)
	if err != nil {
		return "", "", fmt.Errorf("invalid zip archive: %w", err)
	}
	defer zr.Close()

	var tmpConfig, tmpHistory string
	cleanup := func() {
		if tmpConfig != "" {
			_ = os.Remove(tmpConfig)
			tmpConfig = ""
		}
		if tmpHistory != "" {
			_ = os.Remove(tmpHistory)
			tmpHistory = ""
		}
	}

	for _, f := range zr.File {
		if f.FileInfo().IsDir() {
			continue
		}
		cleanName := strings.ReplaceAll(f.Name, "\\", "/")
		if strings.Contains(cleanName, "..") {
			cleanup()
			return "", "", errors.New("zip archive contains invalid path")
		}
		base := path.Base(cleanName)
		switch base {
		case backupConfigName:
			if tmpConfig != "" {
				cleanup()
				return "", "", errors.New("zip archive contains duplicate graywolf.db entries")
			}
			out, err := extractZipEntry(f, configDir, ".graywolf-restore-cfg-*", maxConfigDBBytes)
			if err != nil {
				cleanup()
				return "", "", err
			}
			tmpConfig = out
		case backupHistoryName:
			if historyDir == "" {
				continue
			}
			if tmpHistory != "" {
				cleanup()
				return "", "", errors.New("zip archive contains duplicate graywolf-history.db entries")
			}
			if err := os.MkdirAll(historyDir, 0o755); err != nil {
				cleanup()
				return "", "", err
			}
			out, err := extractZipEntry(f, historyDir, ".graywolf-restore-hist-*", maxHistoryDBBytes)
			if err != nil {
				cleanup()
				return "", "", err
			}
			tmpHistory = out
		}
	}

	if tmpConfig == "" {
		cleanup()
		return "", "", errors.New("backup zip does not contain graywolf.db")
	}
	return tmpConfig, tmpHistory, nil
}

func extractZipEntry(f *zip.File, destDir, pattern string, maxBytes int64) (string, error) {
	rc, err := f.Open()
	if err != nil {
		return "", fmt.Errorf("open %s in zip: %w", f.Name, err)
	}
	defer rc.Close()

	tmp, err := os.CreateTemp(destDir, pattern)
	if err != nil {
		return "", err
	}
	tmpPath := tmp.Name()

	n, copyErr := io.Copy(tmp, io.LimitReader(rc, maxBytes+1))
	closeErr := tmp.Close()
	if copyErr != nil {
		_ = os.Remove(tmpPath)
		return "", copyErr
	}
	if closeErr != nil {
		_ = os.Remove(tmpPath)
		return "", closeErr
	}
	if n > maxBytes {
		_ = os.Remove(tmpPath)
		return "", fmt.Errorf("%s exceeds maximum allowed size (%d MiB)", f.Name, maxBytes>>20)
	}
	return tmpPath, nil
}

func stagePendingFile(src, dst string) error {
	if err := os.Remove(dst); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return os.Rename(src, dst)
}
