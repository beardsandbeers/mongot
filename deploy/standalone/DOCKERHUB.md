# Running the push-mode container

Release 0.3.0 is available as `mybyte/nomongo-t:0.3.0` (Linux amd64). The `latest`,
`mongot-standalone`, and `mongot-standalone-v0.3.0` tags point to this release. Prefer a versioned
tag for reproducible upgrades.

```sh
docker run -d --name mongot -p 127.0.0.1:27029:27029 \
  -v nomongo-t-push_state:/var/lib/mongot mybyte/nomongo-t:0.3.0
docker exec mongot mongotctl status
```

Or build the current sources locally:

```sh
docker compose -f deploy/standalone/compose.yaml up --build --wait
docker compose -f deploy/standalone/compose.yaml exec mongot mongotctl status
```

Or build without Compose:

```sh
docker build -f deploy/standalone/Dockerfile -t mongot-standalone:local .
docker run -d --name mongot -p 127.0.0.1:27029:27029 \
  -v mongot-standalone_state:/var/lib/mongot mongot-standalone:local
docker exec mongot mongotctl status
```

The image includes Java 21, native libraries, the API schema and an authenticated health/status
client. At first startup it generates a TLS certificate and bearer token in
`/var/lib/mongot/config`; those credentials persist across restarts. Export them with `docker cp`
for a client outside the container. The default host port is loopback-only. Supply your own
certificate and restricted index grants for production; see the [operating guide](../../docs/standalone-mongot.md).

Only Lucene content and index metadata persist. Stop the container before copying the entire
volume for backup. Do not run two containers against the same state directory.

Published `mybyte/nomongo-t:0.2.0` images use the older collection-backed implementation and
protocol. Use matching 0.3.x clients and start with a new state volume. Recreate mappings and reingest your own source content. No automatic legacy data migration
or deletion is performed.

For a faster build after `make build.deploy.standalone`, use `make build.docker.standalone.local`.
The ordinary source Docker build always compiles the current checkout.
