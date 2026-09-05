FROM mritd/openjdk17:openj9
ADD target/onelike-backend.jar onelike-backend.jar
CMD java $JAVA_OPTS -Xshareclasses:cacheDir=/shareclasses -Xquickstart -Xtune:virtualized -Dserver.port=8080 -jar /onelike-backend.jar
