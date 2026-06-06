<?php

declare(strict_types=1);

// ──────────────────────────────────────────────────────────────────────────────
// Cloudflare Turnstile secret key.
// Set this value to your real secret from dash.cloudflare.com → Turnstile.
// NEVER commit the real secret; set it here on the server after each deploy.
// ──────────────────────────────────────────────────────────────────────────────
define('TURNSTILE_SECRET', '');

function redirect_home(bool $sent): void
{
    $flag = $sent ? '1' : '0';
    header('Location: /?sent=' . $flag . '#contact', true, 303);
    exit;
}

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    header('Location: /#contact', true, 302);
    exit;
}

// Honeypot
$honeypot = isset($_POST['website']) ? trim((string)$_POST['website']) : '';
if ($honeypot !== '') {
    redirect_home(true);
}

// Cloudflare Turnstile verification
$turnstileToken = isset($_POST['cf-turnstile-response']) ? trim((string)$_POST['cf-turnstile-response']) : '';
if (TURNSTILE_SECRET !== '' && $turnstileToken !== '') {
    $verifyPayload = http_build_query([
        'secret'   => TURNSTILE_SECRET,
        'response' => $turnstileToken,
        'remoteip' => $_SERVER['REMOTE_ADDR'] ?? '',
    ]);
    $ctx = stream_context_create([
        'http' => [
            'method'  => 'POST',
            'header'  => 'Content-Type: application/x-www-form-urlencoded',
            'content' => $verifyPayload,
            'timeout' => 10,
        ],
    ]);
    $result = @file_get_contents('https://challenges.cloudflare.com/turnstile/v0/siteverify', false, $ctx);
    if ($result === false) {
        redirect_home(false);
    }
    $json = json_decode($result, true);
    if (empty($json['success'])) {
        redirect_home(false);
    }
} elseif (TURNSTILE_SECRET !== '' && $turnstileToken === '') {
    // Secret is configured but no token submitted — reject
    redirect_home(false);
}

// Field validation
$type    = isset($_POST['type'])    ? trim((string)$_POST['type'])    : '';
$email   = isset($_POST['email'])   ? trim((string)$_POST['email'])   : '';
$subject = isset($_POST['subject']) ? trim((string)$_POST['subject']) : '';
$message = isset($_POST['message']) ? trim((string)$_POST['message']) : '';

$allowedTypes = ['Feedback', 'Issue', 'InformationRequired'];
if (!in_array($type, $allowedTypes, true) || $email === '' || $subject === '' || $message === '') {
    redirect_home(false);
}

if (!filter_var($email, FILTER_VALIDATE_EMAIL)) {
    redirect_home(false);
}

$cleanType    = $type;
$cleanEmail   = substr(strip_tags($email), 0, 180);
$cleanSubject = substr(strip_tags($subject), 0, 180);
$cleanMessage = substr(str_replace(["\r\n", "\r"], "\n", strip_tags($message)), 0, 4000);

$ip           = $_SERVER['REMOTE_ADDR'] ?? 'unknown';
$userAgent    = $_SERVER['HTTP_USER_AGENT'] ?? 'unknown';
$submittedAt  = gmdate('Y-m-d H:i:s') . ' UTC';

$mailSubject = '[Myrmec] ' . $cleanType . ': ' . $cleanSubject;

$body =
    "New Myrmec website contact form submission\n\n" .
    "Type:        {$cleanType}\n" .
    "Email:       {$cleanEmail}\n" .
    "Subject:     {$cleanSubject}\n" .
    "Submitted:   {$submittedAt}\n" .
    "IP:          {$ip}\n" .
    "User-Agent:  {$userAgent}\n\n" .
    "Message:\n{$cleanMessage}\n";

$headers = implode("\r\n", [
    'From: Myrmec Website <noreply@myrmec.ai>',
    'Reply-To: ' . $cleanEmail,
    'Content-Type: text/plain; charset=UTF-8',
]);

$ok = mail('info@myrmec.ai', $mailSubject, $body, $headers);
redirect_home($ok);
