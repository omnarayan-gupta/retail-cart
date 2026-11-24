# Convenience targets. On Windows use `gradlew.bat` directly or run make from Git Bash.
GRADLE := ./gradlew

.PHONY: build test lint format run deps up down smoke clean

build:   ## Compile, check formatting, run all tests
	$(GRADLE) build

test:    ## Run all tests
	$(GRADLE) test

lint:    ## Check formatting (Spotless)
	$(GRADLE) spotlessCheck

format:  ## Fix formatting (Spotless)
	$(GRADLE) spotlessApply

run:     ## Run the cart app from source on :8081 (start its dependencies first: `make deps`)
	$(GRADLE) bootRun

deps:    ## Start only Postgres + Target product services (for `make run` / IDE)
	docker compose up -d postgres product

up:      ## Build and start everything: Postgres, Target product services, cart app
	docker compose up -d --build

down:    ## Stop containers (cart data is kept; add -v to wipe it)
	docker compose down

smoke:   ## Quick health check of dependencies and the app
	curl -fsS http://localhost:8080/product/v1/health && echo
	curl -fsS http://localhost:8081/actuator/health && echo

clean:
	$(GRADLE) clean
