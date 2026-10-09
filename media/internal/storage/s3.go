package storage

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/url"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

// S3Config names the bucket and how to reach it. Endpoint is a full URL
// (https://hel1.your-objectstorage.com) or a bare host, which means https.
type S3Config struct {
	Endpoint  string
	Region    string
	Bucket    string
	AccessKey string
	SecretKey string
}

// ParseEndpoint splits an endpoint into host and whether to use TLS.
func ParseEndpoint(endpoint string) (host string, secure bool, err error) {
	u, err := url.Parse(endpoint)
	if err != nil || u.Host == "" {
		u, err = url.Parse("https://" + endpoint)
		if err != nil || u.Host == "" {
			return "", false, fmt.Errorf("storage: bad endpoint %q", endpoint)
		}
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return "", false, fmt.Errorf("storage: bad endpoint scheme %q", u.Scheme)
	}
	if u.Path != "" && u.Path != "/" {
		return "", false, fmt.Errorf("storage: endpoint must not carry a path: %q", endpoint)
	}
	return u.Host, u.Scheme == "https", nil
}

type s3Bucket struct {
	client *minio.Client
	bucket string
}

// NewS3 builds a Bucket for an S3-compatible store. It does not talk to the
// network; Ping does.
func NewS3(cfg S3Config) (Bucket, error) {
	if cfg.Bucket == "" || cfg.AccessKey == "" || cfg.SecretKey == "" {
		return nil, errors.New("storage: bucket, access key and secret key are required")
	}
	host, secure, err := ParseEndpoint(cfg.Endpoint)
	if err != nil {
		return nil, err
	}
	client, err := minio.New(host, &minio.Options{
		Creds:  credentials.NewStaticV4(cfg.AccessKey, cfg.SecretKey, ""),
		Secure: secure,
		Region: cfg.Region,
	})
	if err != nil {
		return nil, err
	}
	return &s3Bucket{client: client, bucket: cfg.Bucket}, nil
}

func (b *s3Bucket) Put(ctx context.Context, key string, data []byte, contentType string) error {
	_, err := b.client.PutObject(ctx, b.bucket, key, bytes.NewReader(data), int64(len(data)), minio.PutObjectOptions{ContentType: contentType})
	return err
}

func (b *s3Bucket) Open(ctx context.Context, key string) (io.ReadSeekCloser, error) {
	obj, err := b.client.GetObject(ctx, b.bucket, key, minio.GetObjectOptions{})
	if err != nil {
		return nil, err
	}
	// GetObject is lazy: only Stat tells whether the object exists
	if _, err := obj.Stat(); err != nil {
		obj.Close()
		if minio.ToErrorResponse(err).Code == "NoSuchKey" {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return obj, nil
}

func (b *s3Bucket) Delete(ctx context.Context, key string) error {
	return b.client.RemoveObject(ctx, b.bucket, key, minio.RemoveObjectOptions{})
}

func (b *s3Bucket) List(ctx context.Context) ([]Object, error) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	var out []Object
	for info := range b.client.ListObjects(ctx, b.bucket, minio.ListObjectsOptions{Recursive: true}) {
		if info.Err != nil {
			return nil, info.Err
		}
		out = append(out, Object{Key: info.Key, Modified: info.LastModified})
	}
	return out, nil
}

func (b *s3Bucket) Ping(ctx context.Context) error {
	ok, err := b.client.BucketExists(ctx, b.bucket)
	if err != nil {
		return err
	}
	if !ok {
		return fmt.Errorf("storage: bucket %q does not exist", b.bucket)
	}
	return nil
}
