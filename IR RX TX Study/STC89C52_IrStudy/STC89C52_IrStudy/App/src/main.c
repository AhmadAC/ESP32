#include "main.h"

KeyStruct  keyStr = {KEY_IDLE_STATE, 0, KEY};

//硬件初始化
void HardwareInit(void)
{
	Uart_Init();							//串口初始化
	Timer0_Init();							//定时器初始化
	EA = 1;									//开启中断
}

void main(void)
{
	uint8_t buf[32], id = 0;
	uint16_t bufLen;
	HardwareInit();							//硬件初始化
	while (1) 
	{
		Key_Read();
		switch (Key_Scan(&keyStr)) 
		{
			case KEY_LONG_CLICK:			//长按红外学习
				bufLen = IR_Learn_Pack(buf, id);
				Uart_Send(buf, bufLen);
				break;
			case KEY_SHORT_CLICK:			//单击或双击红外发射
			case KEY_DOUBLE_CLICK:
				bufLen = IR_Send_Pack(buf, id);
				Uart_Send(buf, bufLen);
				break;
			default:
				break;
		}
		delay_ms(10);						//延时10毫秒
	}
}