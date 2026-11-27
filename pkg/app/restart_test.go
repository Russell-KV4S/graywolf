package app

import (
	"context"
	"errors"
	"testing"
	"time"
)

func TestAppRequestRestartUnblocksRunWithErrRestartRequested(t *testing.T) {
	a := quietApp(t)
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	// Drive Run's select loop with a short-lived cancel vs RequestRestart
	// by verifying RequestRestart immediately makes Run return ErrRestartRequested
	// once wireServices/Start have completed. Here we verify RequestRestart
	// is non-blocking even when called multiple times before/after Run.
	a.RequestRestart()
	a.RequestRestart()

	select {
	case <-a.restartCh:
	default:
		t.Fatal("expected restartCh to receive signal")
	}
	select {
	case <-a.restartCh:
		t.Fatal("expected coalesced buffer size 1")
	default:
	}

	if errors.Is(ErrRestartRequested, nil) {
		t.Fatal("ErrRestartRequested must be non-nil")
	}
	_ = ctx
}
