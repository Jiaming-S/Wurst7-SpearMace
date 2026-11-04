# SpearMace gym dedicated server, with its bot clients rendering offscreen.
# Built from the repo root by docker-compose.yml.
FROM eclipse-temurin:25-jdk

# Mesa's software GL for the offscreen bot clients
RUN apt-get update && apt-get install -y --no-install-recommends \
		libgl1 libegl1 libgles2 libgl1-mesa-dri libglx-mesa0 \
		libx11-6 libxext6 libxrandr2 libxi6 libxcursor1 libxxf86vm1 \
		libpulse0 libasound2t64 \
	&& rm -rf /var/lib/apt/lists/*

# Same uid as the host user, so the output bind mount stays theirs
ARG UID=1000
ARG GID=1000
RUN (getent group "$GID" || groupadd -g "$GID" gym) \
	&& (id -u "$UID" >/dev/null 2>&1 && usermod -l gym -d /home/gym -m "$(id -un "$UID")" \
		|| useradd -m -u "$UID" -g "$GID" gym) \
	&& mkdir -p /wurst /gym/output && chown -R "$UID:$GID" /wurst /gym
USER gym
ENV GRADLE_USER_HOME=/home/gym/.gradle
WORKDIR /wurst

# Gradle, Loom and Minecraft first, so source changes don't redo them
COPY --chown=gym gradlew gradlew.bat build.gradle settings.gradle gradle.properties modrinth_deps.json ./
COPY --chown=gym gradle gradle
COPY --chown=gym src/main/resources/wurst.accesswidener src/main/resources/
RUN ./gradlew --no-daemon help

COPY --chown=gym . .
# assets and natives for the bot clients too, so the server runs offline
RUN ./gradlew --no-daemon gametestClasses writeGymAgentLaunch configureClientLaunch \
	&& mkdir -p build/run/spearMaceGymServer/spearmace-gym

COPY --chown=gym docker/gym/entrypoint.sh /entrypoint.sh
EXPOSE 25599
ENTRYPOINT ["/entrypoint.sh"]
