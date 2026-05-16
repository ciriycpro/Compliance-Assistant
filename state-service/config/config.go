// Package config — env-driven конфигурация state-service.
package config

import (
	"fmt"
	"time"

	"github.com/caarlos0/env/v10"
)

// Config — параметры запуска state-service.
type Config struct {
	// HTTP
	HTTPHost string `env:"STATE_SERVICE_HTTP_HOST" envDefault:"127.0.0.1"`
	HTTPPort int    `env:"STATE_SERVICE_HTTP_PORT" envDefault:"8770"`

	// Redis
	RedisAddr     string        `env:"REDIS_ADDR" envDefault:"127.0.0.1:6379"`
	RedisPassword string        `env:"REDIS_PASSWORD" envDefault:""`
	RedisDB       int           `env:"REDIS_DB" envDefault:"0"`
	RedisTimeout  time.Duration `env:"REDIS_TIMEOUT" envDefault:"5s"`

	// Auth
	APIKey string `env:"STATE_SERVICE_API_KEY,required"`

	// Lock TTL по умолчанию — если клиент не передал свой
	DefaultLockTTL time.Duration `env:"STATE_SERVICE_DEFAULT_LOCK_TTL" envDefault:"300s"`

	// Trust в логи — печатать ли API key prefix для дебага
	LogAPIKeyPrefix bool `env:"STATE_SERVICE_LOG_API_KEY_PREFIX" envDefault:"false"`
}

// Load — читает env, возвращает Config или ошибку валидации.
func Load() (*Config, error) {
	cfg := &Config{}
	if err := env.Parse(cfg); err != nil {
		return nil, fmt.Errorf("config: %w", err)
	}
	if cfg.HTTPPort < 1 || cfg.HTTPPort > 65535 {
		return nil, fmt.Errorf("config: invalid HTTP port %d", cfg.HTTPPort)
	}
	if len(cfg.APIKey) < 16 {
		return nil, fmt.Errorf("config: API key too short (need >=16 chars)")
	}
	return cfg, nil
}
