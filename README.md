# Balance Lab 🧪💸

Move money. Race requests. Try to double-spend.

A Spring Boot + React playground for exact decimal balances, safe concurrent
transfers, and retries that count once. PostgreSQL keeps the receipts; Redis
caches them; Kafka spreads the news.

**Fire it up** — Docker with Linux containers required:

```powershell
Copy-Item .env.example .env
# Set strong POSTGRES_PASSWORD and GRAFANA_ADMIN_PASSWORD values in .env.
docker compose up --build -d --wait
```

🎮 [Open the lab](http://localhost:8080) · 📖 [Explore the API](http://localhost:8080/swagger-ui/index.html)

**Test the chaos** — Java 21 + Docker:

```powershell
.\mvnw.cmd clean verify
```

🐧 macOS/Linux: use `cp` and `./mvnw`.

[Frontend](frontend/README.md) · [Kubernetes](k8s/README.md) · [Monitoring 📈](monitoring/README.md) · [Full guide](docs/project-guide.md)

Compose and Kubernetes serve the same UI, each with its own data. Local dev only.
