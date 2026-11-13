# Standalone mongot Node/TypeScript client

Install the downloaded npm tarball with Node 22+:

```sh
npm install ./mongot-standalone-client-0.2.0.tgz
```

```javascript
const { StandaloneClient } = require('@mongot/standalone-client');
const client = new StandaloneClient({ address: 'localhost:27029',
  caFile: 'server.crt', tokenFile: 'admin.token' });
client.getStatus({}).then(console.log).finally(() => client.close());
```

Compiled JavaScript, TypeScript declarations, and the schema are included. No repository checkout
or protoc is required. Dependencies are resolved by npm. uint64 fields are decimal strings.
Omit CA/token only for a plaintext development listener. Callers supply stable request IDs and
inspect every write item's state/status; no mutation is automatically retried.

Full API examples: https://github.com/beardsandbeers/mongot/blob/nomongo/clients/README.md
