"""Stands in for `adb` so tests need no phone."""
import sys

args = sys.argv[1:]
if args[:2] == ["mdns", "services"]:
    print("List of discovered mdns services")
    print("adb-FAKE-abc\t_adb-tls-pairing._tcp\t192.168.1.50:33139")
    print("adb-FAKE-abc\t_adb-tls-connect._tcp\t192.168.1.50:44867")
elif args[0] == "pair":
    host_port, code = args[1], args[2]
    if code == "123456":
        print(f"Successfully paired to {host_port} [guid=adb-FAKE-abc]")
    elif code == "000111":
        print(f"error: protocol fault (couldn't read status message) for {code}")
    else:
        print("Failed: Wrong password or connection was dropped")
elif args[0] == "devices":
    print("List of devices attached")
    print("adb-FAKE-abc._adb-tls-connect._tcp\tdevice")
elif args[0] == "connect":
    print("connected to " + args[1])
