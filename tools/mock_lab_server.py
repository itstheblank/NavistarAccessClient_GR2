"""
Giả lập hệ thống server của Lab (ApiGateway + các worker) để test app Android
khi CHƯA có quyền vào môi trường thật. Bám theo "Device MQTT Integration Guide".

  1. HTTP  POST /api/devicecomm/getmqtttoken  (cổng 8080)
     - Kiểm tra Password = base64(sha256(MacAddress + SALT)) → code OK / WRONG_ACCOUNT
  2. MQTT  (qua Mosquitto chạy trên cùng máy, cổng 1883)
     - Nghe iot/devices/#  → in ra + kiểm tra đủ trường bắt buộc
     - Lần đầu thấy 1 MAC gửi healthcheck → gửi push_config (retained) cho MAC đó
     - Gõ lệnh trong terminal để gửi command / push_config / push_biometric xuống thiết bị
     - Kiểm tra mọi command đều được trả command_resp

Lưu ý: Mosquitto dev KHÔNG kiểm tra token (allow_anonymous) — phần xác thực JWT/ACL
chỉ test được trên EMQX thật của Lab.

Cài:  pip install paho-mqtt
Chạy: python mock_lab_server.py --salt test_salt
      (nhập đúng salt này vào ô "Salt" trong Cài đặt của app)
"""
import argparse
import base64
import hashlib
import json
import secrets
import threading
import time
from datetime import datetime, timezone, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import paho.mqtt.client as mqtt

REQUIRED = {
    "healthcheck": {"MacAddress", "DeviceTime", "IsDeviceDisable", "IsEmergencyOpen", "Version", "BioAuthType"},
    "access": {"MacAddress", "BioId", "IdNumber", "AccessTime", "Direction", "AuthenType",
               "IsAuthenSuccess", "FailedCode", "Abnormal", "DoorStatus", "IsResend"},
    "doorstatus": {"MacAddress", "DoorStatus", "DeviceTime", "Abnormal"},
    "command_resp": {"MacAddress", "CmdId", "DeviceTime"},
    "device_sync_bio": {"MacAddress", "DoorId", "LstBioId"},
    "device_received_bio": {"MacAddress", "BioId", "DoorId", "CmdType", "DeviceTime"},
    "sync_required_door": {"MacAddress", "DoorId", "Version", "RequiredDoorIds"},
    "setting_changed": {"MacAddress", "DeviceTime", "OldSettings", "NewSettings"},
}

state = {
    "macs": set(),            # MAC đã thấy
    "current": None,          # MAC đang thao tác
    "pending_cmds": {},       # CmdId -> (CmdType, thời điểm gửi)
    "next_cmd_id": 1000,
    "seen_access": set(),     # (MAC, AccessTime) để phát hiện gửi trùng
}
lock = threading.Lock()


def utc_cmd_time(offset_sec=0):
    """CmdTime: 'yyyy-MM-dd HH:mm:ss' UTC (KHÔNG phải ISO 8601)"""
    return datetime.fromtimestamp(time.time() + offset_sec, timezone.utc).strftime("%Y-%m-%d %H:%M:%S")


def expected_password(mac, salt):
    return base64.b64encode(hashlib.sha256((mac + salt).encode("utf-8")).digest()).decode()


# ----------------------------------------------------------------- HTTP: getmqtttoken
def make_http_handler(salt):
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            if self.path.rstrip("/") != "/api/devicecomm/getmqtttoken":
                self.send_response(404); self.end_headers(); return
            body = self.rfile.read(int(self.headers.get("Content-Length", 0)) or 0)
            try:
                req = json.loads(body or b"{}")
            except json.JSONDecodeError:
                req = {}
            mac, pw = req.get("MacAddress", ""), req.get("Password", "")
            if mac and pw == expected_password(mac, salt):
                resp = {"code": "OK", "message": "Success",
                        "data": {"token": "mock." + secrets.token_urlsafe(24), "username": mac}}
                print(f"[HTTP] Cấp token cho {mac}")
            else:
                resp = {"code": "WRONG_ACCOUNT", "message": "Wrong MAC or password", "data": None}
                print(f"[HTTP] ⚠ WRONG_ACCOUNT cho {mac!r} (sai salt?)")
            data = json.dumps(resp).encode()
            self.send_response(200)  # giống server thật: luôn 200, phải đọc "code"
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, *args):
            pass
    return Handler


# ----------------------------------------------------------------- MQTT
def push_config(client, mac, threshold=0.8, hold_ms=3000, direction="IN"):
    cfg = {
        "BioAuthType": {"IsFace": True, "IsFinger": False, "IsIris": False, "IsIdCard": False,
                        "Direction": direction, "OneIsOpen": True, "OneIsOpenDoorState": False},
        "SECURITY_LEVEL": 1, "IDENTIFY_THRESHOLD": threshold, "LOCKER_TIME_HOLD": hold_ms,
        "ONE_IS_OPEN": True, "OFFLINE_MODE": False, "NTP_SERVER": "", "DEVICE_CODE": "",
    }
    client.publish(f"iot/server/push_config/{mac}", json.dumps(cfg), qos=2, retain=True)
    print(f"[SERVER] → push_config (retained) cho {mac}: Direction={direction}, "
          f"IDENTIFY_THRESHOLD={threshold}, LOCKER_TIME_HOLD={hold_ms}")


def send_command(client, mac, cmd_type, cmd_id=None, offset_sec=0, timeout=30):
    with lock:
        if cmd_id is None:
            cmd_id = state["next_cmd_id"]; state["next_cmd_id"] += 1
        state["pending_cmds"][cmd_id] = (cmd_type, time.time())
    msg = {"MacAddress": mac, "CmdId": cmd_id, "CmdType": cmd_type,
           "CmdTime": utc_cmd_time(offset_sec), "CmdTimeout": timeout}
    client.publish(f"iot/server/command/{mac}", json.dumps(msg), qos=2)
    print(f"[SERVER] → command {cmd_type} #{cmd_id}")
    return cmd_id


def push_biometric(client, mac, image_path, bio_id=1005, name="Nguyen Van A", with_times=True):
    with open(image_path, "rb") as f:
        img_b64 = base64.b64encode(f.read()).decode()
    today_vn = datetime.now(timezone(timedelta(hours=7))).strftime("%Y-%m-%d")
    lst_times = [{"Day": today_vn, "Time": [{"Start": "00:00:00", "End": "23:59:59"}]}] if with_times else []
    msg = {"CmdType": "NEW_BIO", "BioId": bio_id, "DoorId": 12, "IdNumber": f"0010990{bio_id}",
           "FullName": name, "FromDate": "", "ToDate": "", "FromTime": "", "ToTime": "", "ActiveDays": "",
           "LstTimes": lst_times, "FaceTemps": [], "IrFaceTemps": [], "FingerTemps": [], "IrisTemps": [],
           "IsNoTemplate": True, "FaceImg": img_b64, "FingerImg": "", "IrisImg": "",
           "SyncEnded": True, "IsCheckIn": False}
    client.publish(f"iot/server/push_biometric/{mac}", json.dumps(msg), qos=2)
    print(f"[SERVER] → push_biometric NEW_BIO BioId={bio_id} '{name}' ảnh={image_path} "
          f"({len(img_b64) // 1024} KB) LstTimes={'hôm nay cả ngày' if with_times else 'RỖNG'}")


def delete_biometric(client, mac, bio_id):
    msg = {"CmdType": "DELETE_BIO", "BioId": bio_id, "DoorId": 12, "SyncEnded": True}
    client.publish(f"iot/server/push_biometric/{mac}", json.dumps(msg), qos=2)
    print(f"[SERVER] → push_biometric DELETE_BIO BioId={bio_id}")


def on_connect(client, userdata, flags, reason_code, properties=None):
    print(f"[MQTT] Đã kết nối broker ({reason_code}). Đang nghe iot/devices/#")
    client.subscribe("iot/devices/#", qos=2)


def on_message(client, userdata, msg):
    kind = msg.topic.split("/")[-1]
    try:
        data = json.loads(msg.payload.decode("utf-8"))
    except Exception:
        print(f"[DEVICE] ⚠ {msg.topic}: không phải JSON"); return

    missing = REQUIRED.get(kind, set()) - set(data.keys())
    mac = data.get("MacAddress", "?")
    warn = f"  ⚠ THIẾU: {sorted(missing)}" if missing else ""

    with lock:
        new_mac = mac not in state["macs"] and mac != "?"
        if new_mac:
            state["macs"].add(mac)
            if state["current"] is None:
                state["current"] = mac

    if kind == "healthcheck":
        bio = data.get("BioAuthType", {})
        print(f"[DEVICE] healthcheck {mac} door={data.get('DoorStatus')} disable={data.get('IsDeviceDisable')} "
              f"emergency={data.get('IsEmergencyOpen')} dir={bio.get('Direction')} ip={data.get('IpAddress')}{warn}")
        if new_mac:
            push_config(client, mac)
    elif kind == "access":
        key = (mac, data.get("AccessTime"))
        dup = key in state["seen_access"]
        state["seen_access"].add(key)
        res = "OK " if data.get("IsAuthenSuccess") else f"FAIL {data.get('FailedCode') or data.get('FailedReason')}"
        print(f"[DEVICE] access {res} BioId={data.get('BioId')} score={data.get('RgbScore')} "
              f"door={data.get('DoorStatus')} resend={data.get('IsResend')} time={data.get('AccessTime')}"
              f"{'  (TRÙNG → server bỏ qua)' if dup else ''}{warn}")
    elif kind == "command_resp":
        with lock:
            info = state["pending_cmds"].pop(data.get("CmdId"), None)
        tag = f"{info[0]} sau {time.time() - info[1]:.1f}s" if info else "không có trong danh sách chờ"
        print(f"[DEVICE] command_resp #{data.get('CmdId')} ({tag}){warn}")
    else:
        print(f"[DEVICE] {kind}: {json.dumps(data, ensure_ascii=False)}{warn}")


HELP = """
Lệnh:  open | emergency | close | disable | enable      → gửi command
       expired     → gửi REMOTE_OPEN đã quá hạn (app phải bỏ qua nhưng VẪN trả command_resp)
       dup         → gửi lại CmdId vừa dùng (app không được thực hiện 2 lần)
       config [threshold] [hold_ms] [IN|OUT]  → gửi push_config (VD: config 0.75 5000 OUT)
       bio <ảnh.jpg> [BioId] [Tên]  → gửi NEW_BIO kèm FaceImg, LstTimes = hôm nay cả ngày
       bionotime <ảnh.jpg> [BioId]  → như trên nhưng LstTimes RỖNG (app phải trả NO_SCHEDULE)
       delbio <BioId>               → gửi DELETE_BIO
       macs | use <MAC>  → xem / chọn thiết bị
       pending     → các lệnh chưa được trả command_resp
       help | quit
"""


def console(client):
    last_id = None
    print(HELP)
    while True:
        try:
            line = input("> ").strip()
        except (EOFError, KeyboardInterrupt):
            break
        if not line:
            continue
        parts = line.split()
        cmd = parts[0].lower()
        mac = state["current"]
        if cmd in ("quit", "exit"):
            break
        if cmd == "help":
            print(HELP); continue
        if cmd == "macs":
            print(state["macs"] or "(chưa có thiết bị nào gửi healthcheck)"); continue
        if cmd == "use" and len(parts) > 1:
            state["current"] = parts[1].upper(); print("Đang dùng", state["current"]); continue
        if cmd == "pending":
            print(state["pending_cmds"] or "(không có)"); continue
        if mac is None:
            print("Chưa có thiết bị. Chờ app gửi healthcheck (≤ 30s sau khi kết nối)."); continue

        mapping = {"open": "REMOTE_OPEN", "emergency": "EMERGENCY_OPEN", "close": "CLOSE_DOOR",
                   "disable": "DISABLE_DEVICE", "enable": "ENABLE_DEVICE"}
        if cmd in mapping:
            last_id = send_command(client, mac, mapping[cmd])
        elif cmd == "expired":
            last_id = send_command(client, mac, "REMOTE_OPEN", offset_sec=-120, timeout=30)
        elif cmd == "dup":
            if last_id is None:
                print("Chưa gửi lệnh nào")
            else:
                send_command(client, mac, "REMOTE_OPEN", cmd_id=last_id)
        elif cmd == "config":
            th = float(parts[1]) if len(parts) > 1 else 0.8
            hold = int(parts[2]) if len(parts) > 2 else 3000
            d = parts[3].upper() if len(parts) > 3 else "IN"
            push_config(client, mac, th, hold, d)
        elif cmd in ("bio", "bionotime"):
            if len(parts) < 2:
                print("Cú pháp: bio <đường_dẫn_ảnh> [BioId] [Tên]"); continue
            bio_id = int(parts[2]) if len(parts) > 2 else 1005
            name = " ".join(parts[3:]) if len(parts) > 3 else "Nguyen Van A"
            try:
                push_biometric(client, mac, parts[1], bio_id, name, with_times=(cmd == "bio"))
            except OSError as e:
                print("Không mở được ảnh:", e)
        elif cmd == "delbio" and len(parts) > 1:
            delete_biometric(client, mac, int(parts[1]))
        else:
            print("Không hiểu lệnh. Gõ help.")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--salt", default="test_salt")
    ap.add_argument("--http-port", type=int, default=8080)
    ap.add_argument("--broker", default="127.0.0.1")
    ap.add_argument("--broker-port", type=int, default=1883)
    args = ap.parse_args()

    http = ThreadingHTTPServer(("0.0.0.0", args.http_port), make_http_handler(args.salt))
    threading.Thread(target=http.serve_forever, daemon=True).start()
    print(f"[HTTP] ApiGateway giả lập: http://0.0.0.0:{args.http_port}  (salt = {args.salt!r})")

    try:  # paho-mqtt >= 2.0
        client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="mock_lab_server", protocol=mqtt.MQTTv5)
    except AttributeError:  # paho-mqtt 1.x
        client = mqtt.Client(client_id="mock_lab_server", protocol=mqtt.MQTTv5)
    client.on_connect = on_connect
    client.on_message = on_message
    client.connect(args.broker, args.broker_port, keepalive=60)
    client.loop_start()
    try:
        console(client)
    finally:
        client.loop_stop()
        http.shutdown()


if __name__ == "__main__":
    main()
