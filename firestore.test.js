const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

// --- SECURITY BOUNDS TESTS ---

test("Unauthenticated user: cannot read chat sessions", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/sessions`).get());
});

test("Authenticated user: cannot read another user's session", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().doc(`users/${ALICE_UID}/sessions/sess_1`).set({
      userId: ALICE_UID,
      title: "Alice Chat",
      createdAt: new Date(),
      updatedAt: new Date(),
    });
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.doc(`users/${ALICE_UID}/sessions/sess_1`).get());
});

test("Authenticated user: can create and read their own session", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/sessions/sess_1`).set({
      userId: ALICE_UID,
      title: "Sayon AI Test",
      lastMessage: "Hello AI",
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(aliceDb.doc(`users/${ALICE_UID}/sessions/sess_1`).get());
  await assertSucceeds(aliceDb.collection(`users/${ALICE_UID}/sessions`).get());
});

test("Authenticated user: can add and read message in their session", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/sessions/sess_1/messages/msg_1`).set({
      userId: ALICE_UID,
      sessionId: "sess_1",
      sender: "user",
      content: "Hello Sayon AI!",
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/sessions/sess_1/messages/msg_1`).get()
  );
});

test("Authenticated user: cannot spoof userId in message", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/sessions/sess_1/messages/msg_1`).set({
      userId: BOB_UID, // Spoofed userId
      sessionId: "sess_1",
      sender: "user",
      content: "Malicious message",
      createdAt: now,
      updatedAt: now,
    })
  );
});
