<?php
/**
 * Renders a Minecraft skin texture URL's face area as a 64x64 PNG.
 *
 * Companion to BedrockSkinBridge — the plugin's HTTP endpoint
 * (GET /bedrock-skin?name=<name>) hands you the raw texture URL.
 * This script downloads that 64x64 skin sheet, crops the 8x8 face
 * region at (8, 8), overlays the 8x8 hat layer at (40, 8) with
 * alpha blending, scales to the requested size, and serves as PNG
 * with disk caching.
 *
 * Drop this file at the root of your website. Example URL on your site:
 *   /face.php?url=<URL-encoded texture URL>&size=100
 *
 * Query params:
 *   ?url=<encoded texture URL>   required — must be textures.minecraft.net
 *   ?size=<pixels>               optional, 16..256, default 100
 *
 * Cache: cache/bedrock-faces/<sha1(url+size)>.png, 7-day lifetime.
 * Output: image/png with Cache-Control so the browser also caches.
 */

$root = __DIR__;
$cacheDir = $root . '/cache/bedrock-faces';
if (!is_dir($cacheDir)) @mkdir($cacheDir, 0775, true);

$rawUrl = $_GET['url'] ?? '';
$size = max(16, min(256, (int)($_GET['size'] ?? 100)));

// Strict allowlist: only textures.minecraft.net URLs are accepted, so this
// endpoint can't be abused as a generic image proxy.
if (!is_string($rawUrl)
    || (strpos($rawUrl, 'http://textures.minecraft.net/texture/') !== 0
        && strpos($rawUrl, 'https://textures.minecraft.net/texture/') !== 0)
    || preg_match('#[^a-zA-Z0-9:/.\-_]#', $rawUrl)) {
    http_response_code(400);
    header('Content-Type: text/plain');
    echo 'bad url';
    exit;
}

$cacheKey = sha1($rawUrl . '|' . $size);
$cacheFile = $cacheDir . '/' . $cacheKey . '.png';

if (is_file($cacheFile) && (filemtime($cacheFile) + 7 * 86400) > time()) {
    header('Content-Type: image/png');
    header('Cache-Control: public, max-age=604800');
    readfile($cacheFile);
    exit;
}

$bytes = @file_get_contents($rawUrl);
if ($bytes === false) {
    http_response_code(502);
    header('Content-Type: text/plain');
    echo 'fetch failed';
    exit;
}

$src = @imagecreatefromstring($bytes);
if ($src === false) {
    http_response_code(502);
    header('Content-Type: text/plain');
    echo 'decode failed';
    exit;
}

// Skin sheets are 64x64 (or 64x32 legacy). The face occupies the 8x8 block
// at (8, 8) regardless of layout.
$dst = imagecreatetruecolor($size, $size);
imagealphablending($dst, false);
imagesavealpha($dst, true);
$transparent = imagecolorallocatealpha($dst, 0, 0, 0, 127);
imagefilledrectangle($dst, 0, 0, $size, $size, $transparent);

// Face base — alpha blending OFF so the face's own alpha is copied verbatim.
imagecopyresized($dst, $src, 0, 0, 8, 8, $size, $size, 8, 8);

// Hat overlay — alpha blending ON so transparent hat pixels pass through
// to the face below instead of overwriting it.
imagealphablending($dst, true);
imagecopyresized($dst, $src, 0, 0, 40, 8, $size, $size, 8, 8);

imagedestroy($src);

imagepng($dst, $cacheFile);
imagedestroy($dst);

header('Content-Type: image/png');
header('Cache-Control: public, max-age=604800');
readfile($cacheFile);
