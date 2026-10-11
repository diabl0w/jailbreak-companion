SYSTEM_APK  := app/build/outputs/apk/system/release/app-system-release.apk
BOOTSTRAP_APK := app/build/outputs/apk/bootstrap/release/app-bootstrap-release.apk
ASSETS_DIR  := app/src/bootstrap/assets

.PHONY: all system bootstrap install clean

all: install

system:
	./gradlew :app:assembleSystemRelease
	$(if $(filter system,$(MAKECMDGOALS)),adb install -r $(SYSTEM_APK))

$(ASSETS_DIR)/system.apk: system
	mkdir -p $(ASSETS_DIR)
	cp $(SYSTEM_APK) $(ASSETS_DIR)/system.apk

bootstrap: $(ASSETS_DIR)/system.apk
	./gradlew :app:assemblebootstrapRelease
	cp $(BOOTSTRAP_APK) bootstrap.apk
	mv $(ASSETS_DIR)/system.apk system.apk

install: bootstrap
	adb install -r bootstrap.apk || adb install -r system.apk

clean:
	./gradlew clean
	rm -f bootstrap.apk system.apk $(ASSETS_DIR)/system.apk
