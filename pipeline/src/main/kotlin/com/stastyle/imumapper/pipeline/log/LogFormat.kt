package com.stastyle.imumapper.pipeline.log

/**
 * Binary raw-log format, file extension `.imul`. Little-endian throughout.
 *
 * ```
 * header   : "IMUL" (4 bytes)  u16 formatVersion  u16 reserved(0)
 * record   : u8 type  u32 payloadLength  payload[payloadLength]
 * ```
 *
 * Payloads (all timestamps i64 nanoseconds, elapsed-realtime clock):
 *
 * | type | name          | payload                                                        |
 * |------|---------------|----------------------------------------------------------------|
 * | 0x01 | ACCEL         | t, f32 x y z                                                   |
 * | 0x02 | GYRO          | t, f32 x y z                                                   |
 * | 0x03 | MAG           | t, f32 x y z                                                   |
 * | 0x04 | BARO          | t, f32 hPa                                                     |
 * | 0x05 | GAME_ROT      | t, f32 qx qy qz qw, f32 accuracy                               |
 * | 0x06 | ROT_VEC       | t, f32 qx qy qz qw, f32 accuracy                               |
 * | 0x07 | STEP          | t                                                              |
 * | 0x08 | ACCEL_UNCAL   | t, f32 x y z bx by bz                                          |
 * | 0x09 | GYRO_UNCAL    | t, f32 x y z bx by bz                                          |
 * | 0x0A | MAG_UNCAL     | t, f32 x y z bx by bz                                          |
 * | 0x10 | POSE          | t, i64 frameTs, f32 tx ty tz qx qy qz qw, u8 tracking, u8 reason |
 * | 0x11 | POINT_CLOUD   | t, u32 n, n × (f32 x y z confidence)                           |
 * | 0x12 | KEYFRAME      | t, f32 tx ty tz qx qy qz qw, u16 len, utf8 fileName            |
 * | 0x20 | ANNOTATION    | t, u8 kind, u16 len, utf8 note                                 |
 * | 0x21 | EVENT         | t, u8 kind                                                     |
 * | 0x30 | META          | u32 len, utf8 JSON of LogMeta                                  |
 *
 * Readers skip records with unknown types and drop a truncated final record, so a log from a
 * recorder that died mid-write is still readable.
 */
object LogFormat {
    const val MAGIC = "IMUL"
    const val VERSION = 1
    const val HEADER_SIZE = 8
    const val RECORD_HEADER_SIZE = 5
    const val FILE_EXTENSION = "imul"

    const val T_ACCEL = 0x01
    const val T_GYRO = 0x02
    const val T_MAG = 0x03
    const val T_BARO = 0x04
    const val T_GAME_ROT = 0x05
    const val T_ROT_VEC = 0x06
    const val T_STEP = 0x07
    const val T_ACCEL_UNCAL = 0x08
    const val T_GYRO_UNCAL = 0x09
    const val T_MAG_UNCAL = 0x0A
    const val T_POSE = 0x10
    const val T_POINT_CLOUD = 0x11
    const val T_KEYFRAME = 0x12
    const val T_ANNOTATION = 0x20
    const val T_EVENT = 0x21
    const val T_META = 0x30
}
