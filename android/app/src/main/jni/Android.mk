# hev-socks5-tunnel: передаёт трафик из VPN-интерфейса Android в SOCKS5 (byedpi).
# Его JNI-обёртка регистрирует методы на классе com.fuzik.zapret.TProxyService.
HEV_DIR ?= $(call my-dir)/hev-socks5-tunnel

include $(HEV_DIR)/Android.mk
