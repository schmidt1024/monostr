package protocol

import (
	"strconv"

	"github.com/nbd-wtf/go-nostr"
)

// BuildReceipt returns an unsigned kind 9739 event for a payment on in.
// The note (e) is named only when the intent had one, the sender (P) only
// when the intent was not anonymous.
func BuildReceipt(in Intent, amount, createdAt int64) nostr.Event {
	tags := make(nostr.Tags, 0, 6)
	if in.NoteID != "" {
		tags = append(tags, nostr.Tag{"e", in.NoteID})
	}
	tags = append(tags, nostr.Tag{"p", in.Recipient})
	if !in.Anon {
		tags = append(tags, nostr.Tag{"P", in.Sender})
	}
	tags = append(tags,
		nostr.Tag{"amount", strconv.FormatInt(amount, 10)},
		nostr.Tag{"intent", in.ID},
		nostr.Tag{"type", in.Type},
	)
	return nostr.Event{
		Kind:      KindTipReceipt,
		CreatedAt: nostr.Timestamp(createdAt),
		Content:   "",
		Tags:      tags,
	}
}

// BuildDeletion returns an unsigned kind 5 event retracting a receipt.
func BuildDeletion(receiptID string, createdAt int64) nostr.Event {
	return nostr.Event{
		Kind:      KindDeletion,
		CreatedAt: nostr.Timestamp(createdAt),
		Content:   "payment not confirmed within 60 minutes",
		Tags: nostr.Tags{
			{"e", receiptID},
			{"k", strconv.Itoa(KindTipReceipt)},
		},
	}
}
