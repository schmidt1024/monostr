// Package protocol implements the Monostr tip events as seen by the watcher:
// intent validation (foreign input), receipt and deletion building, NIP-65.
package protocol

const (
	KindPaymentInfo = 10037
	KindTipIntent   = 9738
	KindTipReceipt  = 9739
	KindHTTPAuth    = 27235
	KindDeletion    = 5
	KindRelayList   = 10002
)

func isLowerHex(s string, n int) bool {
	if len(s) != n {
		return false
	}
	for i := 0; i < len(s); i++ {
		c := s[i]
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
			return false
		}
	}
	return true
}

func IsHex64(s string) bool { return isLowerHex(s, 64) }
func IsHex16(s string) bool { return isLowerHex(s, 16) }
