import { appendFileSync } from 'node:fs';
import { join } from 'node:path';
import nodemailer from 'nodemailer';
import { config } from './config.js';

let transport = null;
if (config.mailer.host) {
  transport = nodemailer.createTransport({
    host: config.mailer.host,
    port: config.mailer.port,
    secure: config.mailer.secure,
    auth: config.mailer.user ? { user: config.mailer.user, pass: config.mailer.pass } : undefined,
  });
}

const emailHtml = (code) => `<div style="background:#09090B;color:#E4E4E7;font-family:system-ui,sans-serif;padding:32px;border-radius:16px;max-width:420px">
  <h2 style="margin:0 0 8px;color:#E4E4E7">Welcome to ${config.appName}</h2>
  <p style="color:#71717A;margin:0 0 20px">Your verification code is</p>
  <div style="background:#18181B;border:1px solid #27272A;border-radius:12px;padding:16px;font-size:28px;letter-spacing:8px;text-align:center;font-weight:700;color:#E4E4E7">${code}</div>
  <p style="color:#71717A;font-size:13px;margin:20px 0 0">The code expires in 10 minutes. Never share it with anyone.</p>
</div>`;

export async function sendVerification(email, username, code) {
  if (config.mailer.resendKey) {
    const res = await fetch('https://api.resend.com/emails', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${config.mailer.resendKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        from: config.mailer.from || 'TextApp <onboarding@resend.dev>',
        to: [email],
        subject: `${config.appName} verification code`,
        html: emailHtml(code),
        text: `Your ${config.appName} verification code is ${code}. It expires in 10 minutes.`,
      }),
    });
    if (!res.ok) {
      const body = await res.text();
      console.error(`[mailer] Resend failed (${res.status}):`, body);
      throw new Error('could not send verification email');
    }
    return;
  }
  if (transport) {
    await transport.sendMail({
      from: config.mailer.from || config.mailer.user,
      to: email,
      subject: `${config.appName} verification code`,
      html: emailHtml(code),
    });
  } else {
    const line = `${new Date().toISOString()} ${username} <${email}> code=${code}\n`;
    appendFileSync(join(config.dataDir, 'dev-mails.log'), line);
    console.log(`[mailer] verification code for "${username}": ${code}`);
  }
}
