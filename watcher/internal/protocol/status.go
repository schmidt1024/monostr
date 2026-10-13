package protocol

import (
	"strconv"

	"github.com/nbd-wtf/go-nostr"
)

// BuildStatus builds the status of a personal watcher (kind 10038, spec 0.4):
// whom it watches, the watched address, network, chain height and version.
func BuildStatus(recipient, address, network string, height uint64, version string, createdAt int64) nostr.Event {
	return nostr.Event{
		Kind:      KindWatcherStatus,
		CreatedAt: nostr.Timestamp(createdAt),
		Content:   "",
		Tags: nostr.Tags{
			{"p", recipient},
			{"address", address},
			{"network", network},
			{"height", strconv.FormatUint(height, 10)},
			{"version", version},
		},
	}
}
