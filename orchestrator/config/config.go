// Package config — env-driven configuration для orchestrator v1.
// Принципы DEC-014: minimum зависимостей (caarlos0/env), все настройки через env.
package config

import (
	"fmt"
	"time"

	"github.com/caarlos0/env/v10"
)

// Config — runtime-конфиг orchestrator'a.
type Config struct {
	// HTTP-сервер
	HTTPHost string `env:"ORCHESTRATOR_HTTP_HOST" envDefault:"0.0.0.0"`
	HTTPPort int    `env:"ORCHESTRATOR_HTTP_PORT" envDefault:"8769"`

	// Schedule (cron). Пустая строка = schedule отключен, только webhook.
	Schedule string `env:"ORCHESTRATOR_SCHEDULE" envDefault:""`

	// API-key для X-API-Key auth на POST endpoints. Required.
	APIKey string `env:"ORCHESTRATOR_API_KEY,required"`

	// URLs микросервисов mail-stack
	MailServiceURL       string `env:"MAIL_SERVICE_URL"       envDefault:"http://127.0.0.1:8765"`
	AttachmentServiceURL string `env:"ATTACHMENT_SERVICE_URL" envDefault:"http://127.0.0.1:8766"`
	ParserServiceURL     string `env:"PARSER_SERVICE_URL"     envDefault:"http://127.0.0.1:8767"`
	SummaryServiceURL    string `env:"SUMMARY_SERVICE_URL"    envDefault:"http://127.0.0.1:8768"`
	StateServiceURL      string `env:"STATE_SERVICE_URL"      envDefault:"http://127.0.0.1:8770"`

	// API-key для state-service (X-API-Key header)
	StateServiceAPIKey string `env:"STATE_SERVICE_API_KEY,required"`

	// Lock TTL для защиты workflow от двойного клика (секунды)
	WorkflowLockTTLSeconds int `env:"WORKFLOW_LOCK_TTL_SECONDS" envDefault:"300"`

	// Fallback period (часы) когда last_at не записан — например, при первом запуске
	FallbackPeriodHours int `env:"FALLBACK_PERIOD_HOURS" envDefault:"24"`

	// HTTP timeouts для вызовов микросервисов
	ServiceTimeoutSec int `env:"SERVICE_TIMEOUT_SEC" envDefault:"180"`

	// Telegram-доставка (через Agent Caller :3000)
	AgentCallerURL string `env:"AGENT_CALLER_URL" envDefault:"http://127.0.0.1:3000"`
	TelegramChatID string `env:"TELEGRAM_CHAT_ID,required"`

	// WhatsApp — push-алерт перед Telegram-доставкой. Если пустой — WA отключён.
	WhatsAppNumber string `env:"WHATSAPP_NUMBER" envDefault:""`

	// Google Sheets (через Agent Caller или прямой API). На v1 — через Agent Caller.
	SheetsID    string `env:"SHEETS_ID,required"`
	SheetsRange string `env:"SHEETS_RANGE" envDefault:"Дайджест!A:E"`

	// Rate limit per endpoint (requests per minute)
	RateLimitDigestNow int `env:"RATE_LIMIT_DIGEST_NOW" envDefault:"60"`
	RateLimitCheckMail int `env:"RATE_LIMIT_CHECK_MAIL" envDefault:"30"`

	// Период по умолчанию для /digest-now (часы назад от now)
	DefaultPeriodHours int `env:"DEFAULT_PERIOD_HOURS" envDefault:"24"`

	// === Compliance-logic (DEC-0027: statement-vacuum target) ===
	ComplianceLogicURL    string `env:"COMPLIANCE_LOGIC_URL"     envDefault:"https://127.0.0.1:8771"`
	ComplianceLogicAPIKey string `env:"COMPLIANCE_LOGIC_API_KEY" envDefault:""`
	ComplianceLogicCACert string `env:"COMPLIANCE_LOGIC_CA_CERT" envDefault:"/etc/compliance-tls/ca/ca.crt"`

	// Cron для statement-vacuum (UTC). Пустая = только webhook /statement-vacuum-now.
	StatementVacuumSchedule string `env:"STATEMENT_VACUUM_SCHEDULE" envDefault:""`

	// === Summary-prep (DEC-0030: дистилляция длинных документов в email_digest_v1) ===
	SummaryPrepURL    string `env:"SUMMARY_PREP_URL"     envDefault:"http://127.0.0.1:8772"`
	SummaryPrepAPIKey string `env:"SUMMARY_PREP_API_KEY" envDefault:""`

	// Включает шаг distill_attachments в email_digest_v1.
	// false (default) = старый workflow без summary-prep, дайджест уйдёт raw text.
	// true = новый workflow, длинные вложения дистиллируются.
	EnableSummaryPrep bool `env:"ENABLE_SUMMARY_PREP" envDefault:"false"`

	// Порог длины текста (chars) при превышении которого вложение дистиллируется.
	// Должен совпадать с DISTILL_CHAR_THRESHOLD в summary-prep сервисе (по умолчанию 80000).
	DistillThresholdChars int `env:"DISTILL_THRESHOLD_CHARS" envDefault:"80000"`

	// Таймаут на один distill-вызов (секунды). На КЕБ-PDF (~25 сек) с запасом.
	DistillTimeoutSec int `env:"DISTILL_TIMEOUT_SEC" envDefault:"180"`
}

// Load — парсит env-переменные в Config.
func Load() (*Config, error) {
	cfg := &Config{}
	if err := env.Parse(cfg); err != nil {
		return nil, fmt.Errorf("parse env: %w", err)
	}
	return cfg, nil
}

// HTTPAddr — адрес для http.Server.
func (c *Config) HTTPAddr() string {
	return fmt.Sprintf("%s:%d", c.HTTPHost, c.HTTPPort)
}

// ServiceTimeout — timeout для HTTP-вызовов микросервисов.
func (c *Config) ServiceTimeout() time.Duration {
	return time.Duration(c.ServiceTimeoutSec) * time.Second
}

// DistillTimeout — timeout для одного вызова /distill в summary-prep.
func (c *Config) DistillTimeout() time.Duration {
	return time.Duration(c.DistillTimeoutSec) * time.Second
}
