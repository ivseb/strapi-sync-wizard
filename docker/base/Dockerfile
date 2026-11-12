# Hardened runtime base for the Jib-built application image.
# Same Temurin JRE as the upstream image, with every Alpine package upgraded to the
# latest security patch at build time (upstream images can lag behind by days).
# gnupg and coreutils are only used by the upstream image build (JDK signature check,
# __cacert_entrypoint.sh, which Jib replaces with its own entrypoint): not needed at runtime.
#
#   docker build --pull --platform linux/amd64 -t strapi-sync-wizard-base:21-jre-alpine docker/base
#   ./gradlew buildImage -PbaseImage=docker://strapi-sync-wizard-base:21-jre-alpine
FROM eclipse-temurin:21-jre-alpine
RUN apk upgrade --no-cache && apk del --no-cache gnupg coreutils
