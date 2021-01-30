FROM alpine:latest
RUN apk add --no-cache openjdk9-jre-headless git
ENV VERSION 0.6.3-0.18.5
ENTRYPOINT java -jar ithkuilgloss-$VERSION-jar-with-dependencies.jar
COPY target/ithkuilgloss-$VERSION-jar-with-dependencies.jar /
COPY .git .git
COPY resources resources
