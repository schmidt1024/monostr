package walletrpc

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"slices"
	"strings"
)

// RestoreMargin is how many blocks (about one day) before the current height a new wallet starts scanning.
const RestoreMargin = 720

// MaxRestoreLookback is how far (7 days of blocks, the lifetime of an intent)
// a new wallet may start before the current height.
const MaxRestoreLookback = 7 * RestoreMargin

// Sync describes the wallet a Backend manages (spec 0.4 §3.4): its role,
// the fixed node group it may sync through, the node it uses now, and the
// chain height a newly created wallet takes its restore height from.
type Sync struct {
	Role   string                                    // "scan" or "check"
	Group  []string                                  // normalized node list of the role's group
	Node   func() string                             // node the wallet syncs through now
	Height func(ctx context.Context) (uint64, error) // chain height reported by that group
	// LastHeight is the chain height the watcher last recorded (0 = none); a
	// new wallet starts scanning before it so a node-list change loses nothing.
	LastHeight func() uint64
	// Advance moves the group on to its next node (Node then returns it).
	Advance func() string
}

// Backend is the watcher.LWSClient of the personal mode. It is stateless:
// the wallet file keeps every incoming transfer, and the poller matches
// transfers to intents by pid from the store.
type Backend struct {
	wallet *Client
	sync   Sync
	proxy  string
}

// NewBackend returns a backend for the wallet described by s; proxy is the
// host:port handed to set_daemon ("" without proxy).
func NewBackend(c *Client, s Sync, proxy string) *Backend {
	return &Backend{wallet: c, sync: s, proxy: proxy}
}

// WalletName is the wallet file of address for role, bound to the role's node
// group: monostr-<first 12 chars of address>-<role>-<first 8 hex of sha256 over
// the sorted group joined by ",">. A changed node group starts a fresh wallet,
// so a node that left the group cannot have planted history in it; reordering
// the group keeps the wallet.
func WalletName(address, role string, group []string) string {
	sorted := slices.Sorted(slices.Values(group))
	sum := sha256.Sum256([]byte(strings.Join(sorted, ",")))
	return "monostr-" + address[:12] + "-" + role + "-" + hex.EncodeToString(sum[:4])
}

func EventID(pid string) string { return "wr-" + pid }

// EnsureAccount opens the view-only wallet of address or creates it from the
// view key, then points it at the current node of its group.
func (b *Backend) EnsureAccount(ctx context.Context, address, viewKeyHex string) error {
	name := WalletName(address, b.sync.Role, b.sync.Group)
	if err := b.wallet.OpenWallet(ctx, name); err != nil {
		var rpcErr *RPCError
		if !errors.As(err, &rpcErr) {
			return err // wallet-rpc unreachable: do not try to create
		}
		height, err := b.groupHeight(ctx)
		if err != nil {
			return err
		}
		var last uint64
		if b.sync.LastHeight != nil {
			last = b.sync.LastHeight()
		}
		if err := b.wallet.GenerateFromKeys(ctx, name, address, viewKeyHex, restoreHeight(height, last)); err != nil {
			return err
		}
	}
	return b.wallet.SetDaemon(ctx, b.sync.Node(), b.proxy)
}

// groupHeight asks the group's current node for the chain height and, while
// it does not answer, moves the group on to its next node; every node of the
// group is tried once. The error names no node (URLs may carry credentials).
func (b *Backend) groupHeight(ctx context.Context) (uint64, error) {
	for range len(b.sync.Group) {
		if height, err := b.sync.Height(ctx); err == nil {
			return height, nil
		}
		if b.sync.Advance == nil || ctx.Err() != nil {
			break
		}
		b.sync.Advance()
	}
	if err := ctx.Err(); err != nil {
		return 0, err
	}
	return 0, fmt.Errorf("no node of the %s group reports a height", b.sync.Role)
}

// restoreHeight is where a new wallet starts scanning: RestoreMargin blocks
// before the earlier of the node height now and the last recorded height
// (0 = none), but never more than MaxRestoreLookback blocks before now.
func restoreHeight(now, last uint64) uint64 {
	from := now
	if last != 0 && last < now {
		from = last
	}
	return max(sub(from, RestoreMargin), sub(now, MaxRestoreLookback))
}

// sub is a - b, at least 0.
func sub(a, b uint64) uint64 {
	if a > b {
		return a - b
	}
	return 0
}

func (b *Backend) WebhookAdd(_ context.Context, _, paymentID, _, _ string, _ uint32) (string, error) {
	return EventID(paymentID), nil
}

func (b *Backend) WebhookDeleteUUID(context.Context, []string) error           { return nil }
func (b *Backend) ModifyAccountStatus(context.Context, string, []string) error { return nil }
func (b *Backend) Rescan(context.Context, uint64, []string) error              { return nil }
