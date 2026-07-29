import { createApp } from "./app.js";

const port = Number.parseInt(process.env.PORT ?? "3000", 10);

createApp().listen(port, () => {
  console.log(`Phone origin intelligence listening on port ${port}`);
});