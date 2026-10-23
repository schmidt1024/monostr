// Package nostrdm is the thin NIP-17 transport between the media server and
// its operator. It wraps and unwraps direct messages and talks to relays; it
// never decides what a message means.
package nostrdm

import (
	"context"
	"fmt"

	"github.com/nbd-wtf/go-nostr"
	"github.com/nbd-wtf/go-nostr/nip17"
	"github.com/nbd-wtf/go-nostr/nip59"

	"monostr.com/media/internal/review"
)

// wrapFor builds the gift wrap that delivers text to recipient. Only the copy
// for the recipient is returned: the server keeps no DM history of its own, so
// the copy to self would only be noise on the relays.
func wrapFor(ctx context.Context, kr nostr.Keyer, recipient, text string) (nostr.Event, error) {
	_, toThem, err := nip17.PrepareMessage(ctx, text, nil, kr, recipient, nil)
	if err != nil {
		return nostr.Event{}, err
	}
	return toThem, nil
}

// unwrap opens a gift wrap addressed to kr. Sender is the pubkey that signed
// the seal: GiftUnwrap overwrites the rumor's own (unsigned, freely chosen)
// pubkey with it, so a stranger cannot pose as the operator. WrapID is the
// rumor id, which GiftUnwrap recomputes from the signed content: the id on the
// wrap is whatever the relay says, so a replayed message cannot be disguised
// as a new one by changing it.
func unwrap(ctx context.Context, kr nostr.Keyer, wrap nostr.Event) (review.Inbound, error) {
	rumor, err := nip59.GiftUnwrap(wrap, func(other, ciphertext string) (string, error) {
		return kr.Decrypt(ctx, ciphertext, other)
	})
	if err != nil {
		return review.Inbound{}, fmt.Errorf("unwrap %s: %w", wrap.ID, err)
	}
	return review.Inbound{
		WrapID:  rumor.ID,
		WrapAt:  int64(wrap.CreatedAt),
		SentAt:  int64(rumor.CreatedAt),
		Sender:  rumor.PubKey,
		Kind:    rumor.Kind,
		Content: rumor.Content,
	}, nil
}
