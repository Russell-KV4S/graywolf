//go:build android

package app

import (
	"context"

	"github.com/chrissnell/graywolf/pkg/webapi"
)

// platformBtSource adapts the App's live platformsvc.Client to
// webapi.BondedBtDevicesSource. The adapter reads a.platformClient on
// each call rather than capturing it at construction time so a late
// SetPlatformClient call (or a reconnect that swaps the underlying
// client) is reflected immediately — matching the pattern used by the
// other webapi setters wired in pkg/app/wiring.go.
type platformBtSource struct{ app *App }

// BondedBtDevices forwards to the injected platformsvc client and
// converts platformsvc.BondedBtDevice into the webapi wire type. Returns
// an empty (non-nil) slice when the platform client isn't ready yet so
// the handler ships [] rather than 500.
func (p platformBtSource) BondedBtDevices(ctx context.Context) ([]webapi.BondedBtDevice, error) {
	c := p.app.platformClient
	if c == nil {
		return []webapi.BondedBtDevice{}, nil
	}
	devs, err := c.BondedBtDevices(ctx)
	if err != nil {
		return nil, err
	}
	out := make([]webapi.BondedBtDevice, 0, len(devs))
	for _, d := range devs {
		out = append(out, webapi.BondedBtDevice{MAC: d.MAC, Name: d.Name})
	}
	return out, nil
}

// btSourceForWebapi returns the webapi.BondedBtDevicesSource adapter
// backed by the App's platformsvc client. Returned unconditionally on
// Android — the adapter itself handles a nil client gracefully — so the
// handler responds 200 (with [] when the bond set is empty or the client
// isn't connected yet) rather than 501.
func (a *App) btSourceForWebapi() webapi.BondedBtDevicesSource {
	return platformBtSource{app: a}
}
