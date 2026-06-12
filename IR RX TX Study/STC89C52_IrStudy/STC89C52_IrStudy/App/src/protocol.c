#include "protocol.h"

//求校验和
static uint8_t Get_Check(uint8_t *dat, uint16_t len) {
	uint8_t sum = 0;
	uint16_t i;
	for (i = 0; i < len; i++) {
		sum += dat[i];
	}
	return sum;
}

//红外内码学习指令组包
uint16_t IR_Learn_Pack(uint8_t *dat, uint8_t index) {
	uint8_t *p = dat;
	*p++ = FRAME_START;
	*p++ = 0x08;
	*p++ = 0x00;
	*p++ = MODULE_ADDR;
	*p++ = 0x10;
	*p++ = index;
	*p = Get_Check(&dat[3], p - dat - 3);
	p++;
	*p++ = FRAME_END;
	return p - dat;
}

//红外内码发送指令组包
uint16_t IR_Send_Pack(uint8_t *dat, uint8_t index) {
	uint8_t *p = dat;
	*p++ = FRAME_START;
	*p++ = 0x08;
	*p++ = 0x00;
	*p++ = MODULE_ADDR;
	*p++ = 0x12;
	*p++ = index;
	*p = Get_Check(&dat[3], p - dat - 3);
	p++;
	*p++ = FRAME_END;
	return p - dat;
}
