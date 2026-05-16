// Package store — обёртка над Redis для state-service.
//
// Хранит per-chat_id:
//   - last_at: ISO 8601 timestamp последнего успешного дайджеста (без TTL)
//   - lock:    "1" с TTL (для защиты от двойного клика, auto-cleanup при зависании)
//
// Ключи в Redis:
//   state:{chat_id}:last_at
//   state:{chat_id}:lock
package store

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"
)

// ErrNotFound — last_at не задан для chat_id.
var ErrNotFound = errors.New("state: not found")

// ErrLockHeld — попытка захватить уже занятый lock.
var ErrLockHeld = errors.New("state: lock already held")

// Store — хранилище состояния.
type Store struct {
	rdb *redis.Client
}

// New — создаёт Store с настроенным Redis-клиентом.
func New(addr, password string, db int, timeout time.Duration) *Store {
	rdb := redis.NewClient(&redis.Options{
		Addr:         addr,
		Password:     password,
		DB:           db,
		DialTimeout:  timeout,
		ReadTimeout:  timeout,
		WriteTimeout: timeout,
	})
	return &Store{rdb: rdb}
}

// Ping — health check Redis.
func (s *Store) Ping(ctx context.Context) error {
	return s.rdb.Ping(ctx).Err()
}

// Close — закрыть соединения.
func (s *Store) Close() error {
	return s.rdb.Close()
}

// --- last_at ---

func lastAtKey(chatID int64) string {
	return fmt.Sprintf("state:%d:last_at", chatID)
}

// GetLastAt — возвращает ISO 8601 timestamp или ErrNotFound.
func (s *Store) GetLastAt(ctx context.Context, chatID int64) (time.Time, error) {
	v, err := s.rdb.Get(ctx, lastAtKey(chatID)).Result()
	if errors.Is(err, redis.Nil) {
		return time.Time{}, ErrNotFound
	}
	if err != nil {
		return time.Time{}, fmt.Errorf("redis get: %w", err)
	}
	t, err := time.Parse(time.RFC3339, v)
	if err != nil {
		return time.Time{}, fmt.Errorf("parse timestamp %q: %w", v, err)
	}
	return t.UTC(), nil
}

// SetLastAt — сохраняет ISO 8601 timestamp без TTL (постоянное хранение).
func (s *Store) SetLastAt(ctx context.Context, chatID int64, t time.Time) error {
	v := t.UTC().Format(time.RFC3339)
	if err := s.rdb.Set(ctx, lastAtKey(chatID), v, 0).Err(); err != nil {
		return fmt.Errorf("redis set: %w", err)
	}
	return nil
}

// --- lock ---

func lockKey(chatID int64) string {
	return fmt.Sprintf("state:%d:lock", chatID)
}

// AcquireLock — пытается захватить lock с TTL.
// Возвращает ErrLockHeld если lock уже занят.
func (s *Store) AcquireLock(ctx context.Context, chatID int64, ttl time.Duration) error {
	ok, err := s.rdb.SetNX(ctx, lockKey(chatID), "1", ttl).Result()
	if err != nil {
		return fmt.Errorf("redis setnx: %w", err)
	}
	if !ok {
		return ErrLockHeld
	}
	return nil
}

// ReleaseLock — снимает lock. Безопасно вызывать даже если lock не существует.
func (s *Store) ReleaseLock(ctx context.Context, chatID int64) error {
	if err := s.rdb.Del(ctx, lockKey(chatID)).Err(); err != nil {
		return fmt.Errorf("redis del: %w", err)
	}
	return nil
}

// IsLocked — есть ли активный lock для chat_id.
func (s *Store) IsLocked(ctx context.Context, chatID int64) (bool, error) {
	n, err := s.rdb.Exists(ctx, lockKey(chatID)).Result()
	if err != nil {
		return false, fmt.Errorf("redis exists: %w", err)
	}
	return n > 0, nil
}

// LockTTL — оставшееся время жизни lock'а. -1 если ключа нет.
func (s *Store) LockTTL(ctx context.Context, chatID int64) (time.Duration, error) {
	d, err := s.rdb.TTL(ctx, lockKey(chatID)).Result()
	if err != nil {
		return 0, fmt.Errorf("redis ttl: %w", err)
	}
	// redis.Nil case: TTL returns -2 if key doesn't exist
	if d < 0 {
		return -1, nil
	}
	return d, nil
}
