import { buildServer } from "./app.ts";
import { loadConfig } from "./config.ts";

const config = loadConfig();
const app = await buildServer({ config });

try {
  await app.listen({ host: config.host, port: config.port });
} catch (error) {
  app.log.error({ code: "STARTUP_FAILED" }, "server startup failed");
  await app.close();
  process.exitCode = 1;
  if (error instanceof Error && error.message.includes("EADDRINUSE")) {
    process.stderr.write("Server port is already in use.\n");
  }
}
