package api

import (
	"context"
	"errors"
	"net/http"
	"time"

	"monostr.com/media/internal/blossom"
	"monostr.com/media/internal/storage"
)

// serve is GET and HEAD /<sha256>[.ext] (BUD-01). The bucket is private; the
// bytes pass through here, so a deletion or a ban holds at once.
func (s *server) serve(w http.ResponseWriter, r *http.Request) {
	hash, ok := blobHash(r.PathValue("blob"))
	if !ok {
		reject(w, http.StatusNotFound, CodeNotFound, "not found")
		return
	}
	blob, found, err := s.store.Blob(hash)
	if err != nil {
		s.internal(w, "blob lookup", err)
		return
	}
	if !found {
		reject(w, http.StatusNotFound, CodeNotFound, "not found")
		return
	}
	obj, err := s.bucket.Open(r.Context(), hash)
	if errors.Is(err, storage.ErrNotFound) {
		s.log.Error("blob without object", "sha256", hash)
		reject(w, http.StatusNotFound, CodeNotFound, "not found")
		return
	}
	if err != nil {
		s.log.Error("bucket open", "err", err.Error())
		reject(w, http.StatusServiceUnavailable, CodeUnavailable, "storage is unavailable, try again later")
		return
	}
	defer obj.Close()
	h := w.Header()
	h.Set("Content-Type", blob.Type)
	h.Set("Cache-Control", "public, max-age=31536000, immutable")
	h.Set("ETag", `"`+hash+`"`)
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("Content-Security-Policy", "default-src 'none'")
	http.ServeContent(w, r, "", time.Unix(blob.CreatedAt, 0), obj)
}

// remove is DELETE /<sha256> (BUD-12): the pubkey gives up its ownership; the
// picture goes when the last owner has left.
func (s *server) remove(w http.ResponseWriter, r *http.Request) {
	hash, ok := blobHash(r.PathValue("blob"))
	if !ok {
		reject(w, http.StatusNotFound, CodeNotFound, "not found")
		return
	}
	auth, ok := s.authorize(w, r, blossom.ActionDelete, s.now())
	if !ok {
		return
	}
	if !auth.Covers(hash) {
		reject(w, http.StatusUnauthorized, CodeAuth, "the authorization does not name this blob")
		return
	}
	defer s.blobs.lock(hash)()
	wasOwner, blobGone, err := s.store.RemoveOwner(auth.Pubkey, hash)
	if err != nil {
		s.internal(w, "remove owner", err)
		return
	}
	if !wasOwner {
		reject(w, http.StatusNotFound, CodeNotFound, "not found")
		return
	}
	if blobGone {
		ctx, cancel := context.WithTimeout(r.Context(), s.bucketTimeout)
		defer cancel()
		if err := s.bucket.Delete(ctx, hash); err != nil {
			// the row is gone, the object stays; "admin gc" removes it
			s.log.Error("bucket delete", "sha256", hash, "err", err.Error())
		}
	}
	w.WriteHeader(http.StatusNoContent)
}
