#ifndef __UART_H
#define __UART_H
#include "type.h"
#define BAUD 	9600 		//波特率 
#define SMOD 	1 			//是否波特率加倍 

#if SMOD 
	#define TC_VAL (256 - FOSC / 12 / 16 / BAUD)
#else 
	#define TC_VAL (256 - FOSC / 12 / 32 / BAUD) 
#endif 

//串口初始化
void Uart_Init(void);

//串口多字节数据发送
void Uart_Send(uint8_t *dat, uint16_t len);
#endif
