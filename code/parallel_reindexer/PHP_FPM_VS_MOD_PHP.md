# PHP-FPM vs mod_php for Aspen Discovery

**Date:** 2026-04-09  
**Audience:** Aspen Discovery administrators, system operators, hosting providers

---

## Overview

Aspen Discovery can run under two PHP execution models: **mod_php** (embedded in Apache)
or **PHP-FPM** (FastCGI Process Manager, a separate process pool). This document explains
the differences and why PHP-FPM is recommended for production deployments.

---

## How They Work

### mod_php — PHP Embedded in Apache

```
Request → Apache → [PHP module embedded in Apache] → Response
           └── Every Apache worker carries PHP's memory footprint
               even if it's serving a CSS file
```

### PHP-FPM — PHP as a Separate Process Pool

```
Request → Apache → [proxy_fcgi] → PHP-FPM pool (separate processes) → Response
           └── Apache workers stay lightweight
               PHP pool scales independently
```

---

## Comparison

| Aspect | mod_php | PHP-FPM |
|--------|---------|---------|
| **Memory** | Every Apache worker loads PHP (~50-100MB each), even for static files | Only FPM workers carry PHP memory; Apache stays lean |
| **Concurrency** | Limited by Apache worker count (shared with static files) | PHP pool size is independent of Apache — can tune separately |
| **Performance** | Slightly faster for single requests (no IPC overhead) | Better under load — Apache handles static files without PHP bloat |
| **Isolation** | PHP crash can take down Apache | PHP crash doesn't affect Apache; FPM auto-restarts workers |
| **Configuration** | `php.ini` only, per-server | Per-pool configs (`pm.max_children`, `pm.max_requests`) — can run multiple pools |
| **Scaling** | Scale Apache = scale PHP (can't separate) | Scale each independently |
| **Used by** | Older bare-metal Aspen installs, shared hosting | Docker/ADB deployments, modern production setups |

---

## What Aspen Discovery Uses Today

### Docker / ADB — PHP-FPM

The Aspen Docker images (ADB) ship with PHP-FPM configured:

```ini
pm = dynamic
pm.max_children = 6        ; max 6 concurrent PHP requests
pm.start_servers = 2       ; 2 workers on startup
pm.min_spare_servers = 2
pm.max_spare_servers = 5
pm.max_requests = 500      ; worker recycled after 500 requests (prevents memory leaks)
```

For a production site like CLEVNET with heavy traffic across 30+ libraries, `pm.max_children`
should be increased to 20-50+. At the default of 6, the 7th concurrent user waits in a queue.

### Bare-Metal Installs — mod_php

Many existing production Aspen sites run mod_php because it was the default when those
servers were originally set up.

---

## Why It Ended Up This Way

This is not an intentional architectural decision — it's historical drift:

1. **The original Aspen installs were bare-metal Linux servers** — When Aspen (originally
   Turning Leaf/Pika) was first deployed, the standard Ubuntu/Debian LAMP setup was
   `apt install libapache2-mod-php` — that's mod_php. It was the default, it worked,
   nobody questioned it.

2. **Docker came later** — When they containerized Aspen (ADB), they built the Docker image
   from scratch using modern best practices. PHP-FPM is the standard for containers because
   it separates concerns and plays better with process management.

3. **The install scripts reflect the bare-metal era** — If you look at the Aspen install
   documentation and `createSite.php`, they were written for direct-on-server installs
   where mod_php was the norm.

4. **Nobody went back and changed production** — Existing production sites running mod_php
   work fine. There's no urgent reason to migrate them. The libraries using Aspen aren't
   hitting concurrency limits that would force the switch (most library catalogs don't get
   massive simultaneous traffic).

---

## Recommendation

**Should production use PHP-FPM?** Yes, especially for larger consortia like CLEVNET with
30+ libraries. The benefits:

- For large consortia, search response times could be impacted by request queuing if
  they're hitting mod_php's Apache worker limit during peak hours
- PHP-FPM's `pm.max_children` gives explicit control over PHP concurrency separate
  from Apache's static file serving
- Worker recycling (`pm.max_requests = 500`) prevents memory leaks from accumulating
  over days/weeks
- A PHP segfault doesn't take down the web server

---

## Migrating from mod_php to PHP-FPM

For Aspen administrators looking to switch an existing bare-metal install:

### 1. Install PHP-FPM

```bash
# Debian/Ubuntu (adjust PHP version as needed)
sudo apt install php8.2-fpm
sudo a2dismod php8.2
sudo a2enmod proxy_fcgi setenvif
sudo a2enconf php8.2-fpm
```

### 2. Configure the FPM Pool

Edit `/etc/php/8.2/fpm/pool.d/www.conf`:

```ini
[www]
user = www-data
group = www-data
listen = /run/php/php8.2-fpm.sock

pm = dynamic
pm.max_children = 25          ; adjust for your server's RAM
pm.start_servers = 5
pm.min_spare_servers = 3
pm.max_spare_servers = 10
pm.max_requests = 500

; Security: hide PHP version
php_admin_value[expose_php] = Off
```

**Sizing `pm.max_children`:** Each PHP-FPM worker uses ~50-100MB. For a server with 4GB
available for PHP: `4096MB / 100MB = ~40 max_children`. Start conservative and monitor.

### 3. Restart Services

```bash
sudo systemctl restart php8.2-fpm
sudo systemctl restart apache2
```

### 4. Verify

```bash
# Check FPM is running
sudo systemctl status php8.2-fpm

# Check Apache is proxying to FPM (not using mod_php)
php -r "echo php_sapi_name();"
# Should output: fpm-fcgi (not apache2handler)
```

---

## Tuning Recommendations by Site Size

| Site Size | pm.max_children | pm.start_servers | Notes |
|-----------|----------------|-----------------|-------|
| Small (1 library, <100k records) | 5-10 | 2 | Default is fine |
| Medium (5-10 libraries, 500k records) | 15-25 | 5 | Monitor during peak hours |
| Large consortium (30+ libraries, 1M+ records) | 30-50 | 10 | Consider dedicated PHP server |
| Very large (CLEVNET-scale, multi-million records) | 50-100 | 20 | Separate web and PHP tiers recommended |
