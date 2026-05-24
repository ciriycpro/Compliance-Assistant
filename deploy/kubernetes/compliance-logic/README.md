# Kubernetes manifests — compliance-logic

K8s deployment manifests для compliance-logic. **Намерение, не активный deployment.**

Сейчас compliance-logic работает на coo через systemd. Эти манифесты — для миграции в managed K8s (GKE/EKS) когда придёт время.

## Структура

| Файл | Назначение |
|---|---|
| `namespace.yaml` | Создаёт namespace `compliance-assistant` |
| `configmap.yaml` | Non-secret config (URLs, ports, logging levels) |
| `secret.yaml.template` | Шаблон Secret для POSTGRES_PASSWORD и API_KEY. В production через ESO/Vault |
| `pvc.yaml` | PersistentVolumeClaim 50Gi для `/var/lib/compliance-files/` |
| `deployment.yaml` | 1 replica, recreate strategy, healthchecks, security context (non-root, read-only fs, drop capabilities) |
| `service.yaml` | ClusterIP на порт 8771 — только внутренний доступ |
| `network-policy.yaml` | Только orchestrator → compliance-logic. Egress только в Postgres + DNS |

## Применение (когда K8s будет)

```bash
# 1. Создать namespace
kubectl apply -f namespace.yaml

# 2. Подготовить secrets (заменить PLACEHOLDER на base64)
echo -n "$POSTGRES_PASSWORD" | base64
echo -n "$API_KEY" | base64
# Вписать в secret.yaml.template и применить
kubectl apply -f secret.yaml.template

# 3. Применить configmap и PVC
kubectl apply -f configmap.yaml
kubectl apply -f pvc.yaml

# 4. Deployment + Service
kubectl apply -f deployment.yaml
kubectl apply -f service.yaml

# 5. NetworkPolicy (требует CNI с поддержкой — Calico/Cilium)
kubectl apply -f network-policy.yaml
```

## Готовность

- ✅ Security context: non-root, read-only fs, dropped capabilities (DEC-017 Уровень 0)
- ✅ Health/readiness probes (Spring Actuator)
- ✅ Resource requests + limits (для scheduler)
- ✅ Recreate strategy (PVC ReadWriteOnce совместимо)
- ✅ ConfigMap + Secret split (12-factor)
- ✅ NetworkPolicy: deny-by-default + explicit allow для orchestrator
- ❌ HorizontalPodAutoscaler — не нужен (1 replica, stateful)
- ❌ Ingress — внутренний сервис, не публичный
- ❌ PodDisruptionBudget — будет когда 2+ replicas

См. DEC-016 + DEC-017 Уровень 1.
