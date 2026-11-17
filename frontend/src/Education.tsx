import { useState } from 'react';
import { timestamp, type Snapshot } from './api';
import { useText } from './i18n';

export function RequestPath() {
  const t = useText();
  return <section className="panel education"><h2>{t('Conceptual request path', 'مسیر مفهومی درخواست')}</h2>
    <p>{t('Source-based explanation, not a trace of the selected transaction. No measured durations or correlation spans are available.', 'این توضیح بر پایه کد است و ردگیری تراکنش انتخاب‌شده نیست. زمان اندازه‌گیری‌شده و داده همبستگی در دسترس نیست.')}</p>
    <ol className="path-steps">{[
      t('Validate request fields and account IDs.', 'اعتبارسنجی فیلدها و شناسه حساب‌ها.'),
      t('Acquire JVM lock stripes in stripe-index order.', 'دریافت قفل‌های نواری JVM به ترتیب شاخص نوار.'),
      t('Begin database transaction; lock rows in account-ID order.', 'شروع تراکنش پایگاه داده؛ قفل ردیف‌ها به ترتیب شناسه حساب.'),
      t('Look up completed transaction ID in Redis / JDBC.', 'جست‌وجوی شناسه تراکنش تکمیل‌شده در Redis و JDBC.'),
      t('New operation: check funds, update balances, write receipt and optional outbox rows.', 'عملیات جدید: بررسی موجودی، تغییر موجودی‌ها، ثبت رسید و در صورت فعال بودن ثبت رویدادها.'),
      t('Commit; cache receipt after commit; release JVM stripes.', 'ثبت نهایی؛ ذخیره رسید در کش پس از ثبت؛ آزادسازی قفل‌های JVM.'),
      t('Read current balance for HTTP response. Subsequent activity may affect this balance.', 'خواندن موجودی فعلی برای پاسخ HTTP؛ عملیات بعدی ممکن است این موجودی را تغییر داده باشد.'),
    ].map((step, i) => <li key={i}>{step}</li>)}</ol>
    <h3>{t('Duplicate and conflict branches', 'شاخه‌های تکرار و تعارض')}</h3><p>{t('An identical completed payload skips mutation. A conflicting ID is rejected. A failed attempt does not permanently consume an ID. A client timeout leaves the outcome unknown.', 'اطلاعات یکسان و تکمیل‌شده دوباره موجودی را تغییر نمی‌دهد. شناسه متعارض رد می‌شود. تلاش ناموفق شناسه را برای همیشه مصرف نمی‌کند. قطع ارتباط، نتیجه را نامشخص باقی می‌گذارد.')}</p>
    <h3>{t('Separate scheduled relay', 'ارسال‌کننده زمان‌بندی‌شده مستقل از درخواست')}</h3><p>{t('Relay lock → pending event → Kafka send / ACK → delete outbox row → relay transaction commit. A financial commit does not prove Kafka delivery. Transfers create two logical events when recording is enabled; delivery can repeat or be observed partially.', 'قفل ارسال‌کننده ← رویداد معلق ← ارسال به Kafka و تأیید ← حذف ردیف صف ← ثبت تراکنش ارسال‌کننده. ثبت مالی اثبات تحویل به Kafka نیست. انتقال در صورت فعال بودن ثبت، دو رویداد منطقی ایجاد می‌کند؛ تحویل ممکن است تکراری یا مشاهده آن ناقص باشد.')}</p>
  </section>;
}

export function Structure() {
  const t = useText();
  const components = [
    { id: 'api', name: 'HTTP / BalanceServiceImpl', kind: t('In-process module', 'ماژول داخل برنامه'), description: t('Validates operations and coordinates atomic mutations. Depends on lock stripes, JDBC and receipt lookup.', 'اعتبارسنجی و هماهنگی تغییرات اتمیک؛ وابسته به قفل‌های نواری، JDBC و جست‌وجوی رسید.'), source: 'service/BalanceServiceImpl.java' },
    { id: 'locks', name: 'AccountLockManager', kind: t('In-process module', 'ماژول داخل برنامه'), description: t('Bounded ordered JVM stripes. Database row locks protect state across application instances.', 'قفل‌های نواری محدود و مرتب JVM؛ قفل ردیف پایگاه داده از موجودی بین نمونه‌های برنامه محافظت می‌کند.'), source: 'concurrency/AccountLockManager.java' },
    { id: 'relay', name: 'KafkaOutboxPublisher', kind: t('In-process scheduled module', 'ماژول زمان‌بندی‌شده داخل برنامه'), description: t('Reads the shared pending queue and sends committed events to Kafka. A blocked head delays later sends.', 'صف مشترک رویدادهای معلق را می‌خواند و به Kafka می‌فرستد. توقف ابتدای صف، ارسال‌های بعدی را به تأخیر می‌اندازد.'), source: 'events/KafkaOutboxPublisher.java' },
    { id: 'pg', name: 'PostgreSQL', kind: t('Separate Compose service', 'سرویس جداگانه در Compose'), description: t('Authority for accounts, durable receipts, pending outbox and relay lock. No delivery archive exists in deleted outbox rows.', 'مرجع حساب‌ها، رسیدهای پایدار، صف رویداد و قفل ارسال‌کننده. ردیف حذف‌شده صف، بایگانی تحویل نیست.'), source: 'repository/JdbcAccountRepository.java' },
    { id: 'redis', name: 'Redis', kind: t('Separate Compose service', 'سرویس جداگانه در Compose'), description: t('Accelerates idempotency reads; durable JDBC receipts remain authoritative during cache fallback.', 'جست‌وجوی درخواست تکراری را سریع می‌کند؛ هنگام جایگزینی کش، رسیدهای پایدار JDBC مرجع هستند.'), source: 'idempotency/RedisIdempotencyStore.java' },
    { id: 'kafka', name: 'Kafka', kind: t('Separate Compose service', 'سرویس جداگانه در Compose'), description: t('Asynchronous at-least-once transport. No durable educational observer, lag evidence or delivery archive is connected.', 'انتقال ناهمگام با تحویل حداقل یک‌بار. مشاهده‌گر آموزشی پایدار، شواهد تأخیر مصرف یا بایگانی تحویل متصل نیست.'), source: 'events/KafkaEventConfiguration.java' },
  ];
  const [selected, setSelected] = useState('api');
  const detail = components.find(c => c.id === selected)!;
  return <section className="panel education"><h2>{t('Configured structure', 'ساختار پیکربندی‌شده')}</h2><p>{t('Curated from source and compose.yml. This is not automatic discovery or observed component health.', 'بر اساس کد و compose.yml؛ این نمایش کشف خودکار یا وضعیت سلامت مشاهده‌شده نیست.')}</p>
    <h3>{t('Logical modules · one Java process', 'ماژول‌های منطقی · یک پردازش Java')}</h3><div className="component-map">{components.slice(0, 3).map(c => <button key={c.id} aria-pressed={selected === c.id} onClick={() => setSelected(c.id)}>{c.name}</button>)}</div>
    <h3>{t('Physical dependencies', 'وابستگی‌های فیزیکی')}</h3><div className="component-map">{components.slice(3).map(c => <button key={c.id} aria-pressed={selected === c.id} onClick={() => setSelected(c.id)}>{c.name}</button>)}</div>
    <div className="component-detail" aria-live="polite"><h3><bdi>{detail.name}</bdi></h3><span className="tag">{detail.kind}</span><p>{detail.description}</p><p>{t('Source', 'منبع')}: <code dir="ltr">src/main/java/com/challenge/balance/{detail.source}</code></p></div>
  </section>;
}

export function Monitoring({ snapshot, stale }: { snapshot?: Snapshot; stale: boolean }) {
  const t = useText();
  return <section className="panel education"><h2>{t('Technical monitoring', 'پایش فنی')}</h2><p><bdi>{snapshot ? timestamp(snapshot.capturedAt) : t('No observations yet', 'هنوز مشاهده‌ای وجود ندارد')}</bdi> · {stale ? t('STALE', 'قدیمی') : t('Latest database snapshot', 'آخرین تصویر پایگاه داده')}</p>
    <dl className="evidence-list">
      <div><dt>{t('API / database', 'API و پایگاه داده')}</dt><dd>{snapshot ? t('Snapshot query succeeded at the timestamp above; this is not a readiness probe.', 'پرس‌وجوی تصویر در زمان بالا موفق بود؛ این یک بررسی آمادگی سرویس نیست.') : t('Unknown', 'نامشخص')}</dd></div>
      <div><dt>{t('Redis', 'Redis')}</dt><dd>{t('Unknown: cache health and fallback telemetry are not exposed.', 'نامشخص: سلامت کش و سنجه‌های حالت جایگزین ارائه نشده است.')}</dd></div>
      <div><dt>{t('Event publishing', 'ارسال رویداد')}</dt><dd>{snapshot ? snapshot.kafkaEnabled ? t('Configured enabled; broker availability and actual delivery unknown.', 'طبق پیکربندی فعال؛ دسترس‌پذیری کارگزار و تحویل واقعی نامشخص است.') : t('Configured disabled; historical delivery unknown.', 'طبق پیکربندی غیرفعال؛ تحویل تاریخی نامشخص است.') : t('Unknown', 'نامشخص')}</dd></div>
      <div><dt>{t('Pending outbox rows', 'ردیف‌های معلق صف')}</dt><dd>{snapshot?.pendingEvents ?? '—'} · {t('Shared database queue, not a delivery ledger. Oldest age and blocked head are unavailable.', 'صف مشترک پایگاه داده؛ بایگانی تحویل نیست. سن قدیمی‌ترین رویداد و ابتدای مسدود صف در دسترس نیست.')}</dd></div>
      {['HTTP p50 / p95 / p99', 'JVM / Hikari / PostgreSQL locks', 'Structured logs / traces', 'Kafka observer / lag / event delivery'].map(name => <div key={name}><dt><bdi>{name}</bdi></dt><dd>{t('Unavailable: requires backend instrumentation and a bounded evidence API.', 'در دسترس نیست: به ابزارگذاری سمت سرور و رابط محدود شواهد نیاز دارد.')}</dd></div>)}
    </dl><p>{t('Browser round trips are available in the API inspector. They are not server latency measurements. Expected business rejections are separate from technical failures.', 'زمان رفت‌وبرگشت مرورگر در بازرس API موجود است و اندازه‌گیری تأخیر سرور نیست. رد شدن تجاری با خطای فنی تفاوت دارد.')}</p>
  </section>;
}
