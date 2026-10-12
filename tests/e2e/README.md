# Browser tests

Run against a stack that is already up (web on `E2E_BASE_URL`, default `http://localhost:3000`, proxying `/api/v1` to
the API; the database the API uses in `E2E_DATABASE_URL`).

```bash
cd tests/e2e
pnpm install && pnpm install-browsers
E2E_BASE_URL=http://localhost:3000 pnpm test
```

The tests register fresh addresses (`e2e-xxxxxxxx@example.de`) and confirm them directly in the database, because
the confirmation mail cannot be read from a browser. Do not point them at a production database.
