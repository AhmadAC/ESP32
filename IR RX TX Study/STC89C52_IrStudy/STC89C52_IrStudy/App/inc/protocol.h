#ifndef __PROTOCOL_H
#define __PROTOCOL_H
#include "type.h"

#define FRAME_START		0x68
#define FRAME_END		0x16
#define MODULE_ADDR		0xff

//红外内码学习指令组包
uint16_t IR_Learn_Pack(uint8_t *dat, uint8_t index);

//红外内码发送指令组包
uint16_t IR_Send_Pack(uint8_t *dat, uint8_t index);
#endif
