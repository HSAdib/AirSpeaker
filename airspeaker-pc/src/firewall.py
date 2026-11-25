"""
firewall.py — Cross-platform firewall helper (Windows netsh & Linux ufw/firewalld/iptables).
"""

import logging
import shutil
import subprocess
import sys

from config import clean_subprocess_env

logger = logging.getLogger(__name__)

PORTS = [5004, 5005]
RULE_NAME_TMPL = "AirSpeaker UDP {port}"


def _netsh_add_cmd(port: int) -> list:
    return [
        "netsh", "advfirewall", "firewall", "add", "rule",
        f"name={RULE_NAME_TMPL.format(port=port)}",
        "protocol=UDP",
        f"localport={port}",
        "dir=in",
        "action=allow",
        "enable=yes",
        "profile=any",
    ]


def _netsh_delete_cmd(port: int) -> list:
    return [
        "netsh", "advfirewall", "firewall", "delete", "rule",
        f"name={RULE_NAME_TMPL.format(port=port)}",
    ]


def get_firewall_commands() -> list:
    if sys.platform == "darwin":
        return ["sudo /usr/libexec/ApplicationFirewall/socketfilterfw --add /Applications/AirSpeaker.app/Contents/MacOS/AirSpeaker"]
    if sys.platform.startswith("linux"):
        if shutil.which("ufw"):
            return [f"sudo ufw allow {p}/udp" for p in PORTS]
        if shutil.which("firewall-cmd"):
            return [f"sudo firewall-cmd --add-port={p}/udp --permanent" for p in PORTS] + ["sudo firewall-cmd --reload"]
        return [f"sudo iptables -I INPUT -p udp --dport {p} -j ACCEPT" for p in PORTS]
    return [" ".join(_netsh_add_cmd(port)) for port in PORTS]


def apply_firewall_rules() -> tuple:
    if sys.platform == "darwin":
        return True, "macOS Application Firewall automatically permits UDP 5004 and 5005 when AirSpeaker is launched."

    if sys.platform.startswith("linux"):
        cmds = []
        if shutil.which("ufw"):
            cmds = [f"ufw allow {p}/udp" for p in PORTS]
        elif shutil.which("firewall-cmd"):
            cmds = [f"firewall-cmd --add-port={p}/udp --permanent" for p in PORTS] + ["firewall-cmd --reload"]
        else:
            cmds = [f"iptables -I INPUT -p udp --dport {p} -j ACCEPT" for p in PORTS]

        shell_str = " && ".join(cmds)
        if shutil.which("pkexec"):
            try:
                ret = subprocess.run(["pkexec", "sh", "-c", shell_str], capture_output=True, text=True, timeout=30, env=clean_subprocess_env())
                if ret.returncode == 0:
                    return True, "Linux firewall rules applied for UDP ports 5004 and 5005."
            except Exception as exc:
                logger.error("Linux pkexec firewall error: %s", exc)
        return False, "Run in terminal:\n" + "\n".join(get_firewall_commands())

    # Windows implementation
    results = []
    direct_ok = True
    for port in PORTS:
        del_cmd = _netsh_delete_cmd(port)
        try:
            subprocess.run(del_cmd, capture_output=True, text=True, timeout=5)
        except Exception:
            pass
        cmd = _netsh_add_cmd(port)
        try:
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=5)
            if result.returncode == 0 or "already exists" in (result.stdout + result.stderr).lower():
                results.append(f"Port {port}: OK")
            else:
                direct_ok = False
                break
        except Exception:
            direct_ok = False
            break

    if direct_ok and results:
        return True, "\n".join(results)

    try:
        import base64
        ps_commands = "; ".join([
            f"netsh advfirewall firewall delete rule name='AirSpeaker UDP {p}'; netsh advfirewall firewall add rule name='AirSpeaker UDP {p}' protocol=UDP localport={p} dir=in action=allow enable=yes profile=any"
            for p in PORTS
        ])
        b64 = base64.b64encode(ps_commands.encode("utf-16le")).decode("ascii")
        elevate_cmd = [
            "powershell.exe", "-NoProfile", "-WindowStyle", "Hidden", "-Command",
            f"Start-Process powershell.exe -Verb RunAs -WindowStyle Hidden -Wait -ArgumentList '-NoProfile -EncodedCommand {b64}'"
        ]
        ret = subprocess.run(elevate_cmd, capture_output=True, text=True, timeout=20)
        if ret.returncode == 0:
            return True, "Firewall rules requested via administrator prompt.\nPorts 5004 & 5005 allowed on all profiles."
    except Exception as exc:
        logger.error("Failed to run elevated firewall command: %s", exc)

    return False, (
        "Administrator elevation was cancelled or failed.\n"
        "Please run manually as Administrator:\n"
        + "\n".join(get_firewall_commands())
    )
