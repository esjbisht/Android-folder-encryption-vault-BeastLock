const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_001";
const BOB_UID = "bob_uid_002";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
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

test("Unauthenticated reads and writes are rejected on all collections", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const past = new Date(Date.now() - 2000);

  await assertFails(unauthDb.collection("custom_security_questions").get());
  await assertFails(
    unauthDb.collection("custom_security_questions").doc("q_1").set({
      userId: ALICE_UID,
      questionText: "What was my first bike model?",
      locale: "en-US",
      appVersion: "1.0",
      createdAt: past,
      updatedAt: past,
    })
  );

  await assertFails(unauthDb.collection("diagnostic_reports").get());
  await assertFails(
    unauthDb.collection("diagnostic_reports").doc("rep_1").set({
      userId: ALICE_UID,
      category: "USER_FEEDBACK",
      errorCode: "NONE",
      sanitizedSummary: "Manual diagnostic report",
      userFeedback: "App works smoothly",
      healthSnapshot: "folders=2;corrupted=0",
      appVersion: "1.0",
      androidSdk: 35,
      deviceModel: "Pixel 8",
      occurredAtMillis: Date.now() - 5000,
      createdAt: past,
      updatedAt: past,
    })
  );
});

test("Owner can create, query, and update valid custom_security_questions; cross-user and extra keys rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("custom_security_questions").doc("q_alice_1").set({
      userId: ALICE_UID,
      questionText: "Where did I graduate high school?",
      locale: "en-US",
      appVersion: "1.0",
      createdAt: created,
      updatedAt: created,
    })
  );

  // Scoped query succeeds; unfiltered query fails
  await assertSucceeds(
    aliceDb.collection("custom_security_questions").where("userId", "==", ALICE_UID).get()
  );
  await assertFails(aliceDb.collection("custom_security_questions").get());

  // Cross-user isolation
  await assertFails(bobDb.collection("custom_security_questions").doc("q_alice_1").get());
  await assertFails(
    bobDb.collection("custom_security_questions").doc("q_alice_1").update({
      questionText: "Hijacked question?",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Shadow field / secret answer rejection
  await assertFails(
    aliceDb.collection("custom_security_questions").doc("q_alice_bad").set({
      userId: ALICE_UID,
      questionText: "Forbidden answer test?",
      answer: "secret123",
      locale: "en-US",
      appVersion: "1.0",
      createdAt: created,
      updatedAt: created,
    })
  );

  // Partial update preserves createdAt
  await assertSucceeds(
    aliceDb.collection("custom_security_questions").doc("q_alice_1").update({
      questionText: "Which city did I graduate in?",
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(
    aliceDb.collection("custom_security_questions").doc("q_alice_1").update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Owner can create and update diagnostic_reports; invalid category, future timestamp, and cross-user reads fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("diagnostic_reports").doc("rep_alice_1").set({
      userId: ALICE_UID,
      category: "CRYPTO_ERROR",
      errorCode: "VLK1_EOF_MISMATCH",
      sanitizedSummary: "Detected truncated encrypted archive header during folder verification",
      userFeedback: "Happened after battery died during lock.",
      healthSnapshot: "folders=3;locked=2;corrupted=1;dualKey=SYNCED",
      appVersion: "1.0",
      androidSdk: 35,
      deviceModel: "Pixel 8",
      occurredAtMillis: Date.now() - 10000,
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    aliceDb.collection("diagnostic_reports").where("userId", "==", ALICE_UID).get()
  );
  await assertFails(aliceDb.collection("diagnostic_reports").get());
  await assertFails(bobDb.collection("diagnostic_reports").doc("rep_alice_1").get());

  // Tampered future timestamp rejected
  await assertFails(
    aliceDb.collection("diagnostic_reports").doc("rep_future").set({
      userId: ALICE_UID,
      category: "USER_FEEDBACK",
      errorCode: "NONE",
      sanitizedSummary: "Future timestamp attempt",
      userFeedback: "Test",
      healthSnapshot: "ok",
      appVersion: "1.0",
      androidSdk: 35,
      deviceModel: "Pixel 8",
      occurredAtMillis: Date.now() - 5000,
      createdAt: new Date(Date.now() + 60000),
      updatedAt: new Date(Date.now() + 60000),
    })
  );

  // Invalid category rejected
  await assertFails(
    aliceDb.collection("diagnostic_reports").doc("rep_bad_cat").set({
      userId: ALICE_UID,
      category: "UNKNOWN_CATEGORY",
      errorCode: "ERR",
      sanitizedSummary: "Summary",
      userFeedback: "Test",
      healthSnapshot: "ok",
      appVersion: "1.0",
      androidSdk: 35,
      deviceModel: "Pixel 8",
      occurredAtMillis: Date.now() - 5000,
      createdAt: created,
      updatedAt: created,
    })
  );
});
