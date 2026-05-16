package store

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/redis/go-redis/v9"
)

// helper: создать Store с подключением к реальному Redis на localhost.
// Тесты пропускаются если Redis недоступен (DB=15 — отдельный namespace для тестов).
func newTestStore(t *testing.T) *Store {
	t.Helper()
	rdb := redis.NewClient(&redis.Options{
		Addr:        "127.0.0.1:6379",
		DB:          15,
		DialTimeout: 2 * time.Second,
	})
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := rdb.Ping(ctx).Err(); err != nil {
		t.Skipf("Redis not available at 127.0.0.1:6379: %v", err)
	}
	// FlushDB в test-namespace перед каждым тестом
	if err := rdb.FlushDB(ctx).Err(); err != nil {
		t.Fatalf("FlushDB: %v", err)
	}
	return &Store{rdb: rdb}
}

func TestSetGetLastAt(t *testing.T) {
	s := newTestStore(t)
	defer s.Close()
	ctx := context.Background()

	chatID := int64(123456)
	t0 := time.Date(2026, 5, 16, 14, 30, 0, 0, time.UTC)

	if err := s.SetLastAt(ctx, chatID, t0); err != nil {
		t.Fatalf("SetLastAt: %v", err)
	}
	got, err := s.GetLastAt(ctx, chatID)
	if err != nil {
		t.Fatalf("GetLastAt: %v", err)
	}
	if !got.Equal(t0) {
		t.Errorf("got %v, want %v", got, t0)
	}
}

func TestGetLastAt_NotFound(t *testing.T) {
	s := newTestStore(t)
	defer s.Close()
	ctx := context.Background()

	_, err := s.GetLastAt(ctx, 999999)
	if !errors.Is(err, ErrNotFound) {
		t.Errorf("expected ErrNotFound, got %v", err)
	}
}

func TestSetLastAt_Overwrites(t *testing.T) {
	s := newTestStore(t)
	defer s.Close()
	ctx := context.Background()

	chatID := int64(42)
	t1 := time.Date(2026, 5, 16, 10, 0, 0, 0, time.UTC)
	t2 := time.Date(2026, 5, 16, 14, 30, 0, 0, time.UTC)

	_ = s.SetLastAt(ctx, chatID, t1)
	_ = s.SetLastAt(ctx, chatID, t2)

	got, _ := s.GetLastAt(ctx, chatID)
	if !got.Equal(t2) {
		t.Errorf("got %v, want %v", got, t2)
	}
}

func TestLastAt_NoTTL(t *testing.T) {
	// last_at не должен иметь TTL (persistent storage)
	s := newTestStore(t)
	defer s.Close()
	ctx := context.Background()

	chatID := int64(7)
	_ = s.SetLastAt(ctx, chatID, time.Now().UTC())

	ttl, err := s.rdb.TTL(ctx, lastAtKey(chatID)).Result()
	if err != nil {
		t.Fatalf("TTL: %v", err)
	}
	// -1 = no expiration
	if ttl != -1*time.Nanosecond && ttl != -1*time.Second {
		// Redis возвращает -1s для ключей без TTL
		if ttl > 0 {
			t.Errorf("last_at должен быть без TTL, got %v", ttl)
		}
	}
}

func TestAcquireLock_Success(t *testing.T) {
	s := newTestStore(t)
	defer s.Close()
	ctx := context.Background()

	chatID := int64(100)
	if err := s.AcquireLock(ctx, chatID, 30*time.Second); err != nil {
		t.Fatalf("AcquireLock: %v", err)
	}

	locked, err := s.IsLocked(ctx, chatID)
	if err != nil {
		t.Fatalf("IsLocked: %v", err)
	}
	if !locked {
		t.Error("expected locked, got false")
	}
}

func TestAcquireLock_AlreadyHeld(t *testing.T) {
	s := newTestStore(t)
	defer s.Close()
	ctx := context.Background()

	chatID := int64(200)
	_ = s.AcquireLock(ctx, chatID, 30*time.Second)

	err := s.AcquireLock(ctx, chatID, 30*time.Second)
	if !errors.Is(err, ErrLockHeld) {
		t.Errorf("expected ErrLockHeld, got %v", err)
	}
}

func TestReleaseLock(t *testing.T) {
	s := newTestStore(t)
	defer s.Close()
	ctx := context.Background()

	chatID := int64(300)
	_ = s.AcquireLock(ctx, chatID, 30*time.Second)
	if err := s.ReleaseLock(ctx, chatID); err != nil {
		t.Fatalf("ReleaseLock: %v", err)
	}

	locked, _ := s.IsLocked(ctx, chatID)
	if locked {
		t.Error("expected unlocked, got locked")
	}
}

func TestReleaseLock_Idempotent(t *testing.T) {
	// Release несуществующего lock'а не должен падать
	s := newTestStore(t)
	defer s.Close()
	ctx := context.Background()

	if err := s.ReleaseLock(ctx, 999); err != nil {
		t.Errorf("ReleaseLock on non-existent lock failed: %v", err)
	}
}

func TestLock_AutoExpires(t *testing.T) {
	// Lock должен авто-сниматься через TTL
	s := newTestStore(t)
	defer s.Close()
	ctx := context.Background()

	chatID := int64(400)
	_ = s.AcquireLock(ctx, chatID, 1*time.Second)

	time.Sleep(1500 * time.Millisecond)

	locked, _ := s.IsLocked(ctx, chatID)
	if locked {
		t.Error("expected lock to expire, but still locked")
	}
}

func TestLockTTL(t *testing.T) {
	s := newTestStore(t)
	defer s.Close()
	ctx := context.Background()

	chatID := int64(500)
	_ = s.AcquireLock(ctx, chatID, 60*time.Second)

	ttl, err := s.LockTTL(ctx, chatID)
	if err != nil {
		t.Fatalf("LockTTL: %v", err)
	}
	// Должен быть близко к 60 секундам (могут быть мс расхождения)
	if ttl < 55*time.Second || ttl > 60*time.Second {
		t.Errorf("expected ~60s TTL, got %v", ttl)
	}
}

func TestLockTTL_NoLock(t *testing.T) {
	s := newTestStore(t)
	defer s.Close()
	ctx := context.Background()

	ttl, err := s.LockTTL(ctx, 9999)
	if err != nil {
		t.Fatalf("LockTTL: %v", err)
	}
	if ttl >= 0 {
		t.Errorf("expected -1 for missing key, got %v", ttl)
	}
}

func TestPing(t *testing.T) {
	s := newTestStore(t)
	defer s.Close()
	ctx := context.Background()

	if err := s.Ping(ctx); err != nil {
		t.Errorf("Ping failed: %v", err)
	}
}
