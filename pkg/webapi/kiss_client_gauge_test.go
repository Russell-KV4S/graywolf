package webapi

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"strconv"
	"sync"
	"testing"
	"time"

	"github.com/chrissnell/graywolf/pkg/kiss"
	"github.com/chrissnell/graywolf/pkg/webapi/dto"
)

// TestNotifyKissManager_TCPReportsClientChange is the regression test for
// graywolf#548: graywolf_kiss_clients_active stuck at 0 after any hot
// reload of a tcp (server-listen) KISS interface.
//
// The gauge's only writer was ServerConfig.OnClientChange, set solely by
// the kissComponent boot literal in pkg/app/wiring.go. notifyKissManager
// builds its own ServerConfig for the tcp arm and never set the field, so
// the replacement Server had a nil hook and addClient/removeClient
// reported nothing until the next restart. It landed on 0 rather than a
// stale count because the outgoing server's handleClient goroutines run
// their deferred removeClient as the context cancels.
//
// The fix makes the hook manager-owned, so it is installed on every
// Server the Manager starts regardless of which dispatch site built the
// config. This test therefore drives the hot-reload path end to end --
// create through the real HTTP handler, dial the real listener -- and
// asserts the observable outcome rather than inspecting the config that
// was passed. Same reasoning as the #546 test alongside it: a struct
// literal omission is exactly what a field-by-field mock assertion can be
// written to miss.
func TestNotifyKissManager_TCPReportsClientChange(t *testing.T) {
	srv, _ := newTestServer(t)

	type report struct {
		name   string
		active int
	}
	var mu sync.Mutex
	var reports []report
	record := func(_ uint32, name string, active int) {
		mu.Lock()
		reports = append(reports, report{name: name, active: active})
		mu.Unlock()
	}
	// Wait for a report matching pred, returning false on timeout.
	awaitReport := func(pred func(report) bool) bool {
		for deadline := time.Now().Add(3 * time.Second); time.Now().Before(deadline); {
			mu.Lock()
			for _, r := range reports {
				if pred(r) {
					mu.Unlock()
					return true
				}
			}
			mu.Unlock()
			time.Sleep(5 * time.Millisecond)
		}
		return false
	}

	mgr := kiss.NewManager(kiss.ManagerConfig{
		Logger:         slog.New(slog.NewTextHandler(io.Discard, nil)),
		Sink:           &recordingSink{},
		OnClientChange: record,
	})
	srv.kissManager = mgr
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(func() {
		cancel()
		mgr.StopAll()
	})
	srv.kissCtx = ctx

	mux := http.NewServeMux()
	srv.RegisterRoutes(mux)

	// createKiss dispatches through notifyKissManager -- the same path a
	// later PUT takes -- so the create alone exercises the bug.
	port := freeTCPPort(t)
	body, _ := json.Marshal(map[string]any{
		"type":       "tcp",
		"tcp_port":   port,
		"local_only": true,
		"channel":    1,
		"mode":       "modem",
	})
	rr := doPost(mux, "/api/kiss", body)
	if rr.Code != http.StatusCreated {
		t.Fatalf("create tcp interface: %d %s", rr.Code, rr.Body.String())
	}
	var created dto.KissResponse
	if err := json.NewDecoder(rr.Body).Decode(&created); err != nil {
		t.Fatalf("decode create response: %v", err)
	}
	// dto derives the display name from the port; it is the gauge label,
	// so assert on it rather than accepting any name.
	wantName := fmt.Sprintf("kiss-tcp-%d", port)

	conn := dialKissWhenReady(t, "127.0.0.1:"+strconv.Itoa(port))
	defer conn.Close()

	if !awaitReport(func(r report) bool { return r.name == wantName && r.active == 1 }) {
		mu.Lock()
		got := fmt.Sprintf("%v", reports)
		mu.Unlock()
		t.Fatalf("no OnClientChange report of %q active=1 after a client connected; "+
			"notifyKissManager's tcp arm produced a Server with no client reporter, "+
			"so graywolf_kiss_clients_active stays 0 until the next restart (got %s)", wantName, got)
	}

	// Disconnecting must report back down, or the gauge would latch high.
	_ = conn.Close()
	if !awaitReport(func(r report) bool { return r.name == wantName && r.active == 0 }) {
		t.Errorf("no OnClientChange report of %q active=0 after the client "+
			"disconnected; the gauge would latch at its last value", wantName)
	}
}

// dialKissWhenReady retries until the manager's listener is bound. The
// server binds on its own goroutine, so a create returning 201 does not
// mean the socket is accepting yet.
func dialKissWhenReady(t *testing.T, addr string) net.Conn {
	t.Helper()
	for deadline := time.Now().Add(3 * time.Second); time.Now().Before(deadline); {
		c, err := net.Dial("tcp", addr)
		if err == nil {
			return c
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("kiss server never became connectable on %s", addr)
	return nil
}
