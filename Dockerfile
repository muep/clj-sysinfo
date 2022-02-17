FROM openjdk:11
COPY target/clj-sysinfo.jar /
CMD ["java", "-jar", "/clj-sysinfo.jar"]
