# ============================================================================
# compVDO — Makefile
# ============================================================================
# `make build`   — bump patch version, build debug APK, copy to ./build/
# `make release` — bump patch version, build release APK (unsigned)
# `make clean`   — clean Gradle caches
# `make version` — show current version
# ============================================================================

ANDROID_DIR  := android
VERSION_FILE := $(ANDROID_DIR)/version.properties
BUILD_DIR    := build
GRADLEW      := cd $(ANDROID_DIR) && ./gradlew

# ---------------------------------------------------------------------------
# Read current version from version.properties
# ---------------------------------------------------------------------------
MAJOR  := $(shell grep '^major='  $(VERSION_FILE) | cut -d= -f2)
MINOR  := $(shell grep '^minor='  $(VERSION_FILE) | cut -d= -f2)
PATCH  := $(shell grep '^patch='  $(VERSION_FILE) | cut -d= -f2)
BUILD  := $(shell grep '^buildNumber=' $(VERSION_FILE) | cut -d= -f2)

# Next version (patch + 1)
NEXT_PATCH := $(shell echo $$(( $(PATCH) + 1 )))
NEXT_BUILD := $(shell echo $$(( $(BUILD) + 1 )))
NEXT_VERSION := $(MAJOR).$(MINOR).$(NEXT_PATCH)

# ---------------------------------------------------------------------------
# Targets
# ---------------------------------------------------------------------------

.PHONY: build release clean version bump-version ensure-sdk

## Show current version
version:
	@echo "compVDO v$(MAJOR).$(MINOR).$(PATCH) (build $(BUILD))"

## Bump patch version in version.properties (no git involved)
bump-version:
	@echo "Bumping version: $(MAJOR).$(MINOR).$(PATCH) → $(NEXT_VERSION) (build $(NEXT_BUILD))"
	@printf 'major=$(MAJOR)\nminor=$(MINOR)\npatch=$(NEXT_PATCH)\nbuildNumber=$(NEXT_BUILD)\n' > $(VERSION_FILE)

## Ensure local.properties points to the SDK
ensure-sdk:
	@if [ ! -f $(ANDROID_DIR)/local.properties ]; then \
		echo "sdk.dir=$(ANDROID_HOME)" > $(ANDROID_DIR)/local.properties; \
		echo "Created local.properties with sdk.dir=$(ANDROID_HOME)"; \
	fi

## Build debug APK (bumps version first)
build: bump-version ensure-sdk
	@echo "=== Building compVDO v$(NEXT_VERSION) debug APK ==="
	$(GRADLEW) assembleDebug --no-daemon
	@mkdir -p $(BUILD_DIR)
	@cp $(ANDROID_DIR)/app/build/outputs/apk/debug/app-debug.apk \
		$(BUILD_DIR)/compvdo-$(NEXT_VERSION)-debug.apk
	@echo ""
	@echo "✅ APK ready: $(BUILD_DIR)/compvdo-$(NEXT_VERSION)-debug.apk"
	@echo "   Version: $(NEXT_VERSION) (build $(NEXT_BUILD))"

## Build release APK (unsigned, bumps version first)
release: bump-version ensure-sdk
	@echo "=== Building compVDO v$(NEXT_VERSION) release APK ==="
	$(GRADLEW) assembleRelease --no-daemon
	@mkdir -p $(BUILD_DIR)
	@cp $(ANDROID_DIR)/app/build/outputs/apk/release/app-release-unsigned.apk \
		$(BUILD_DIR)/compvdo-$(NEXT_VERSION)-release.apk 2>/dev/null || \
	cp $(ANDROID_DIR)/app/build/outputs/apk/release/app-release.apk \
		$(BUILD_DIR)/compvdo-$(NEXT_VERSION)-release.apk
	@echo ""
	@echo "✅ APK ready: $(BUILD_DIR)/compvdo-$(NEXT_VERSION)-release.apk"
	@echo "   Version: $(NEXT_VERSION) (build $(NEXT_BUILD))"

## Clean build artifacts
clean:
	$(GRADLEW) clean --no-daemon
	rm -rf $(BUILD_DIR)
	@echo "✅ Cleaned."
