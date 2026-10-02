BASE_URL ?= http://localhost:8080

.PHONY: build test up down burst

build:        ## compile + package (no Docker needed)
	./mvnw -B package

test:         ## concurrency + ops integration tests (needs Docker)
	./mvnw -B verify

up:           ## app + postgres on :8080
	docker compose up --build -d

down:
	docker compose down -v

burst:        ## make burst [BASE_URL=https://your-app.onrender.com]
	./burst.sh $(BASE_URL)
