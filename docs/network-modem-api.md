# OpenEMS Network & Modem API Documentation

Hướng dẫn sử dụng JSON-RPC API cho `io.openems.edge.network.manager` và `io.openems.edge.modem.4g`.

## Thông tin chung

- **Endpoint**: `http://<IP>:8084/jsonrpc`
- **Method**: POST
- **Content-Type**: `application/json`
- **Authentication**: Basic Auth (admin:admin hoặc user/password đã cấu hình)

---

## 1. Network Manager (`network0`)

### 1.1 Channels có thể lấy

| Channel ID | Type | Description |
|------------|------|-------------|
| `Lan1Ip` | String | IP address của eth0 |
| `Lan2Ip` | String | IP address của eth1 |
| `MobileIp` | String | IP address của 4G modem |
| `LastErrorMessage` | String | Thông báo lỗi cuối cùng |

### 1.2 Lấy giá trị Channel (HTTP GET)

```bash
# Lấy IP của eth0
GET http://localhost:8084/rest/channel/network0/Lan1Ip

# Lấy IP của eth1
GET http://localhost:8084/rest/channel/network0/Lan2Ip

# Lấy IP của 4G
GET http://localhost:8084/rest/channel/network0/MobileIp
```

### 1.3 JSON-RPC: getNetworkManagerStatus

Lấy trạng thái IP của tất cả interfaces.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "id": "UUID",
  "method": "componentJsonApi",
  "params": {
    "componentId": "network0",
    "payload": {
      "jsonrpc": "2.0",
      "id": "UUID",
      "method": "getNetworkManagerStatus",
      "params": {}
    }
  }
}
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": "UUID",
  "result": {
    "lan1Ip": "192.168.1.100",
    "lan2Ip": "10.0.0.50",
    "mobileIp": "10.1.2.3",
    "lastError": null
  }
}
```

### 1.4 JSON-RPC: getNetworkManagerConfig

Lấy cấu hình hiện tại của eth0/eth1.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "id": "UUID",
  "method": "componentJsonApi",
  "params": {
    "componentId": "network0",
    "payload": {
      "jsonrpc": "2.0",
      "id": "UUID",
      "method": "getNetworkManagerConfig",
      "params": {}
    }
  }
}
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": "UUID",
  "result": {
    "eth0": {
      "mode": "STATIC",
      "ip": "192.168.1.100",
      "subnetMask": "255.255.255.0",
      "gateway": "192.168.1.1",
      "dnsPrimary": "8.8.8.8",
      "dnsSecondary": "8.8.4.4"
    },
    "eth1": {
      "mode": "DHCP",
      "ip": "",
      "subnetMask": "",
      "gateway": "",
      "dnsPrimary": "",
      "dnsSecondary": ""
    }
  }
}
```

### 1.5 JSON-RPC: setNetworkManagerConfig

Cài đặt cấu hình cho interface.

**Request (Static IP):**
```json
{
  "jsonrpc": "2.0",
  "id": "UUID",
  "method": "componentJsonApi",
  "params": {
    "componentId": "network0",
    "payload": {
      "jsonrpc": "2.0",
      "id": "UUID",
      "method": "setNetworkManagerConfig",
      "params": {
        "interfaceName": "eth0",
        "mode": "STATIC",
        "ip": "192.168.1.200",
        "subnetMask": "255.255.255.0",
        "gateway": "192.168.1.1"
      }
    }
  }
}
```

**Request (DHCP):**
```json
{
  "jsonrpc": "2.0",
  "id": "UUID",
  "method": "componentJsonApi",
  "params": {
    "componentId": "network0",
    "payload": {
      "jsonrpc": "2.0",
      "id": "UUID",
      "method": "setNetworkManagerConfig",
      "params": {
        "interfaceName": "eth0",
        "mode": "DHCP"
      }
    }
  }
}
```

### 1.6 Create/Update Component (OSGi Configuration)

**Create Component:**
```json
{
  "jsonrpc": "2.0",
  "id": "UUID",
  "method": "createComponentConfig",
  "params": {
    "factoryPid": "Network.Manager",
    "properties": [
      { "name": "id", "value": "network0" },
      { "name": "alias", "value": "Network Manager" },
      { "name": "enabled", "value": true },
      { "name": "eth0Mode", "value": "STATIC" },
      { "name": "eth0Ip", "value": "192.168.1.100" },
      { "name": "eth0SubnetMask", "value": "255.255.255.0" },
      { "name": "eth0Gateway", "value": "192.168.1.1" },
      { "name": "eth0DnsPrimary", "value": "8.8.8.8" },
      { "name": "eth0DnsSecondary", "value": "8.8.4.4" },
      { "name": "eth1Mode", "value": "DHCP" }
    ]
  }
}
```

**Update Component:**
```json
{
  "jsonrpc": "2.0",
  "id": "UUID",
  "method": "updateComponentConfig",
  "params": {
    "componentId": "network0",
    "properties": [
      { "name": "eth0Ip", "value": "192.168.1.200" }
    ]
  }
}
```

---

## 2. Modem 4G Manager (`modem4g0`)

### 2.1 Channels có thể lấy

| Channel ID | Type | Description |
|------------|------|-------------|
| `ModemStatus` | Enum | Trạng thái: DISCONNECTED, CONNECTING, CONNECTED, ERROR, NO_MODEM |
| `SignalStrength` | Integer (%) | Cường độ tín hiệu 0-100% |
| `SignalDbm` | Integer | Tín hiệu dBm |
| `OperatorName` | String | Tên nhà mạng (Viettel, MobiFone...) |
| `IpAddress` | String | IP được cấp |
| `CurrentApn` | String | APN đang sử dụng |
| `ConnectionType` | String | Loại kết nối: LTE, 3G, 2G |
| `ModemModel` | String | Model modem (Quectel EC25...) |
| `Imei` | String | Số IMEI |
| `SimStatus` | String | Trạng thái SIM |
| `LastError` | String | Lỗi cuối cùng |
| `NoModemDetected` | Boolean | Fault nếu không tìm thấy modem |

### 2.2 Lấy giá trị Channel (HTTP GET)

```bash
# Lấy trạng thái modem
GET http://localhost:8084/rest/channel/modem4g0/ModemStatus

# Lấy cường độ tín hiệu
GET http://localhost:8084/rest/channel/modem4g0/SignalStrength

# Lấy IP
GET http://localhost:8084/rest/channel/modem4g0/IpAddress

# Lấy tên nhà mạng
GET http://localhost:8084/rest/channel/modem4g0/OperatorName
```

### 2.3 JSON-RPC: getModemStatus

Lấy toàn bộ trạng thái modem.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "id": "UUID",
  "method": "componentJsonApi",
  "params": {
    "componentId": "modem4g0",
    "payload": {
      "jsonrpc": "2.0",
      "id": "UUID",
      "method": "getModemStatus",
      "params": {}
    }
  }
}
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": "UUID",
  "result": {
    "status": "CONNECTED",
    "signalStrength": 75,
    "operator": "Viettel",
    "ipAddress": "10.1.2.3",
    "connectionType": "LTE",
    "modemModel": "Quectel EC25",
    "imei": "866758041234567",
    "lastError": null
  }
}
```

### 2.4 JSON-RPC: getModemConfig

Lấy cấu hình APN hiện tại.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "id": "UUID",
  "method": "componentJsonApi",
  "params": {
    "componentId": "modem4g0",
    "payload": {
      "jsonrpc": "2.0",
      "id": "UUID",
      "method": "getModemConfig",
      "params": {}
    }
  }
}
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": "UUID",
  "result": {
    "apn": "v-internet",
    "username": "",
    "password": ""
  }
}
```

### 2.5 JSON-RPC: setModemConfig

Cài đặt APN mới. Module sẽ cập nhật file `/etc/ppp/quectel-pppd.sh` và restart kết nối PPP.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "id": "UUID",
  "method": "componentJsonApi",
  "params": {
    "componentId": "modem4g0",
    "payload": {
      "jsonrpc": "2.0",
      "id": "UUID",
      "method": "setModemConfig",
      "params": {
        "apn": "v-internet",
        "username": "",
        "password": ""
      }
    }
  }
}
```

### 2.6 APN phổ biến tại Việt Nam

| Nhà mạng | APN | Username | Password |
|----------|-----|----------|----------|
| Viettel | `v-internet` | (empty) | (empty) |
| MobiFone | `m-wap` | (empty) | (empty) |
| Vinaphone | `m3-world` | (empty) | (empty) |
| Vietnamobile | `internet` | (empty) | (empty) |

### 2.7 Create/Update Component

**Create Component:**
```json
{
  "jsonrpc": "2.0",
  "id": "UUID",
  "method": "createComponentConfig",
  "params": {
    "factoryPid": "Modem.4G.Manager",
    "properties": [
      { "name": "id", "value": "modem4g0" },
      { "name": "alias", "value": "4G Modem" },
      { "name": "enabled", "value": true },
      { "name": "apn", "value": "v-internet" },
      { "name": "username", "value": "" },
      { "name": "password", "value": "" }
    ]
  }
}
```

**Update Component:**
```json
{
  "jsonrpc": "2.0",
  "id": "UUID",
  "method": "updateComponentConfig",
  "params": {
    "componentId": "modem4g0",
    "properties": [
      { "name": "apn", "value": "m-wap" }
    ]
  }
}
```

---

## 3. Lấy tất cả Components

```json
{
  "jsonrpc": "2.0",
  "id": "UUID",
  "method": "getEdgeConfig",
  "params": {}
}
```

---

## 4. Ví dụ sử dụng cURL

### Lấy trạng thái Network Manager:
```bash
curl -X POST http://localhost:8084/jsonrpc \
  -H "Content-Type: application/json" \
  -u admin:admin \
  -d '{
    "jsonrpc": "2.0",
    "id": "1",
    "method": "componentJsonApi",
    "params": {
      "componentId": "networkManager0",
      "payload": {
        "jsonrpc": "2.0",
        "id": "1",
        "method": "getNetworkManagerStatus",
        "params": {}
      }
    }
  }'
```

### Lấy trạng thái Modem 4G:
```bash
curl -X POST http://localhost:8084/jsonrpc \
  -H "Content-Type: application/json" \
  -u admin:admin \
  -d '{
    "jsonrpc": "2.0",
    "id": "1",
    "method": "componentJsonApi",
    "params": {
      "componentId": "modem4g0",
      "payload": {
        "jsonrpc": "2.0",
        "id": "1",
        "method": "getModemStatus",
        "params": {}
      }
    }
  }'
```

### Lấy channel đơn lẻ:
```bash
curl -X GET http://localhost:8084/rest/channel/modem4g0/SignalStrength \
  -u admin:admin
```

---

## 5. Postman Collection

Import các request trên vào Postman:
1. Tạo Collection mới
2. Set Base URL: `http://{{host}}:8084`
3. Set Auth: Basic Auth với username/password
4. Thêm các request theo mẫu trên
