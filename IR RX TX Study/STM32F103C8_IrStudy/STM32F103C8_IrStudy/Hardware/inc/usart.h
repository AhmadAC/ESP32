#ifndef USART_H
#define USART_H
#include "type.h"
#define   BAUD_RATE     115200
void USART1_Config(void);
void Uart_Send(uint8_t *data, uint16_t len);
#endif
