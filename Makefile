# Convenience targets. On Windows use `gradlew.bat` directly or run make from Git Bash.
GRADLE := ./gradlew

.PHONY: build test lint format run up down smoke clean

build:   ## Compile, check formatting, run all tests
	$(GRADLE) build

test:    ## Run all tests
	$(GRADLE) test

lint:    ## Check formatting (Spotless)
	$(GRADLE) spotlessCheck

format:  ## Fix formatting (Spotless)
	$(GRADLE) spotlessApply

run:     ## Run the cart app locally on :8081 (needs Postgres: `make up`)
	$(GRADLE) :apps:app:bootRun

up:      ## Start Postgres + Target product services
	docker compose up -d

down:    ## Stop containers
	docker compose down

smoke:   ## Quick health check of dependencies and the app
	curl -fsS http://localhost:8080/product/v1/health && echo
	curl -fsS http://localhost:8081/actuator/health && echo

clean:
	$(GRADLE) clean
