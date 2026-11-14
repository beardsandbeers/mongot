#!/usr/bin/env node
import * as grpc from '@grpc/grpc-js';
import { createInterface } from 'node:readline';
import { StandaloneClient } from './client';

const methods: Record<string, keyof StandaloneClient> = {
  Upsert: 'upsert',
  Delete: 'delete',
  CreateIndex: 'createIndex',
  UpdateIndex: 'updateIndex',
  DropIndex: 'dropIndex',
  GetIndex: 'getIndex',
  ListIndexes: 'listIndexes',
  GetStatus: 'getStatus',
  Search: 'search',
  VectorSearch: 'vectorSearch',
  GetMore: 'getMore',
  KillCursor: 'killCursor',
};
async function main() {
  const client = new StandaloneClient({ address: process.env.MONGOT_ADDRESS, caFile: process.env.MONGOT_CA,
    tokenFile: process.env.MONGOT_TOKEN_FILE, timeoutMs: Number(process.env.MONGOT_TIMEOUT ?? 30) * 1000 });
  try {
    for await (const line of createInterface({ input: process.stdin })) {
      let output;
      try {
        const call = JSON.parse(line);
        const method = methods[call.method];
        if (!method) throw new Error('Unknown method');
        const response = await (client[method] as (r: object) => Promise<unknown>).call(client, call.request ?? {});
        output = { response };
      } catch (error) {
        const e = error as grpc.ServiceError;
        output = { error: { code: typeof e.code === 'number' ? grpc.status[e.code] : 'CLIENT_ERROR', message: e.details ?? e.message } };
      }
      console.log(JSON.stringify(output, (_key, value) => value?.type === 'Buffer' && Array.isArray(value.data) ? Buffer.from(value.data).toString('base64') : value));
    }
  } finally { client.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
