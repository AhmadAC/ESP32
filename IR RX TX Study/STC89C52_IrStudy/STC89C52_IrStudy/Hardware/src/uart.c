#include "uart.h"

//串口初始化
void Uart_Init(void)
{
	TMOD &= 0x0f;
	TMOD |= 0X20;			//设置计数器工作方式2
	PCON |= 0x80;			//波特率加倍
	SCON = 0X50;			//设置为工作方式1
	TH1 = TC_VAL; 
	TL1 = TH1; 
	TR1 = 1;				//打开计数器                      
}

//串口多字节数据发送
void Uart_Send(uint8_t *dat, uint16_t len) 
{
	uint16_t i;
	for (i = 0; i < len; i++)
	{
		printf("%c", dat[i]);
	}
}
	
char putchar(char c) 
{             
    SBUF = c;        
    while(TI == 0);        
    TI = 0;            
    return c;
}


