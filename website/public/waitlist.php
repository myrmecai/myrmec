<?php

declare(strict_types=1);

function redirect_home(bool $ok): void
{
    header('Location: /?waitlist=' . ($ok ? '1' : '0'), true, 303);
    exit;
}

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    header('Location: /', true, 302);
    exit;
}

$email = isset($_POST['email']) ? trim((string)$_POST['email']) : '';

if ($email === '' || !filter_var($email, FILTER_VALIDATE_EMAIL)) {
    redirect_home(false);
}

$cleanEmail  = substr($email, 0, 180);
$submittedAt = gmdate('Y-m-d H:i:s') . ' UTC';
$ip          = $_SERVER['REMOTE_ADDR'] ?? 'unknown';

$subject = '[Myrmec] New waitlist signup';
$body    = "New waitlist signup\n\nEmail: {$cleanEmail}\nSubmitted: {$submittedAt}\nIP: {$ip}\n";
$headers = implode("\r\n", [
    'From: Myrmec Website <noreply@myrmec.ai>',
    'Reply-To: ' . $cleanEmail,
    'Content-Type: text/plain; charset=UTF-8',
]);

$ok = mail('info@myrmec.ai', $subject, $body, $headers);
redirect_home($ok);
