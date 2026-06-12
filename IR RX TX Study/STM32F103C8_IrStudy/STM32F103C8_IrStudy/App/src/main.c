#include "main.h"

//按键PB0 PB1，前提要保证按键松开状态是高电平，按下去是低电平
KeyStruct keyStrMatrix[] = {
	{KEY_IDLE_STATE, 0, K1},
	{KEY_IDLE_STATE, 0, K2}
};

//硬件初始化
void Init_Hardware(void) {
	RCC_Init();							//时钟初始化
	USART1_Config();					//串口初始化
	LED_Init();							//LED灯初始化 
	Key_Init();							//按键初始化
}

/*
功能：主函数
备注：PB0按键长按2秒学习第一组，单击或者双击发送第一组
	  PB1按键长按2秒学习第一组，单击或者双击发送第一组
*/
//主函数
int main() {
	uint8_t buf[128], i;
	uint16_t bufLen;
	Init_Hardware();					//硬件初始化
    while (1) {
		Key_Read();
		for (i = 0; i < 2; i++) {
			switch (Key_Scan(&keyStrMatrix[i])) {
				case KEY_LONG_CLICK:	//长按2秒进入红外学习
					bufLen = IR_Learn_Pack(buf, i);
					Uart_Send(buf, bufLen);
					break;
				case KEY_SHORT_CLICK:	//单击或者双击红外发射
				case KEY_DOUBLE_CLICK:
					bufLen = IR_Send_Pack(buf, i);
					Uart_Send(buf, bufLen);
					break;
				default:
					break;
			} 
		}
		delay_ms(10);					//延时10毫秒
    }
}
