package app

import (
	"context"
	"testing"

	"github.com/chrissnell/graywolf/pkg/ax25"
	"github.com/chrissnell/graywolf/pkg/txgovernor"
)

// stubTxSink is a no-op txgovernor.TxSink used only to get a non-nil
// interface value distinguishable from the "no governor" case.
type stubTxSink struct{}

func (stubTxSink) Submit(context.Context, uint32, *ax25.Frame, txgovernor.SubmitSource) error {
	return nil
}

// TestIgateTxSinkGatedByGateIsToRf locks the IS->RF master-switch
// contract: the iGate is wired to the governor only when GateIsToRf is
// true, and gets no governor (so it can never transmit to RF) when it is
// false — regardless of the rule table. Both wiring sites in wiring.go
// route through this helper, so this guards the runtime reload path too.
func TestIgateTxSinkGatedByGateIsToRf(t *testing.T) {
	var gov txgovernor.TxSink = stubTxSink{}

	if got := igateTxSink(false, gov); got != nil {
		t.Fatalf("GateIsToRf=false must yield a nil governor, got %v", got)
	}
	if got := igateTxSink(true, gov); got != gov {
		t.Fatalf("GateIsToRf=true must yield the shared governor, got %v", got)
	}
}
