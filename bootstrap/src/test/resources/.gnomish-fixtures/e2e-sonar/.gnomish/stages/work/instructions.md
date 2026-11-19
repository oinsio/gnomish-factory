# Work stage instructions

The fake agent plays one plain round. The round box's own check passes only while the SonarQube
token is absent from it; the quality gate is judged by the `sonarqube` program in a fresh box of its
own, the one place the token is delivered.
