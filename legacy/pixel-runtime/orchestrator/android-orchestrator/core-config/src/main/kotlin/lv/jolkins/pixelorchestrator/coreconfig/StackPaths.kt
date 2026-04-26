package lv.jolkins.pixelorchestrator.coreconfig

object StackPaths {
  const val BASE = "/data/local/pixel-stack"
  const val CHROOT_ADGUARDHOME = "$BASE/chroots/adguardhome"
  @Deprecated("Use CHROOT_ADGUARDHOME")
  const val CHROOT_PIHOLE = CHROOT_ADGUARDHOME
  const val BIN = "$BASE/bin"
  const val CONF = "$BASE/conf"
  const val RUN = "$BASE/run"
  const val LOG = "$BASE/logs"
  const val SSH = "$BASE/ssh"
  const val VPN = "$BASE/vpn"
  const val APPS = "$BASE/apps"
  const val TRAIN_BOT = "$APPS/train-bot"
  const val SATIKSME_BOT = "$APPS/satiksme-bot"
  const val SITE_NOTIFIER = "$APPS/site-notifications"
  const val SUBSCRIPTION_BOT = "$APPS/subscription-bot"
  const val BACKUPS = "$BASE/backups"
  const val ACTION_RESULTS = "$RUN/orchestrator-action-results"

  const val CONFIG_JSON = "$CONF/orchestrator-config-v1.json"
  const val STATE_JSON = "$RUN/orchestrator-state-v1.json"
}
