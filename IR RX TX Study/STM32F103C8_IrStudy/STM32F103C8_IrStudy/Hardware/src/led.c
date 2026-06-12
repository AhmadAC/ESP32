#include "led.h"

void LED_Init(void) {
	GPIO_InitTypeDef GPIO_InitStruct;
	GPIO_InitStruct.GPIO_Speed = GPIO_Speed_50MHz;
	GPIO_InitStruct.GPIO_Mode = GPIO_Mode_Out_PP;
	GPIO_InitStruct.GPIO_Pin = LED1_PIN;
	GPIO_Init(LED1_GPIO, &GPIO_InitStruct);
	GPIO_InitStruct.GPIO_Pin = LED2_PIN;
	GPIO_Init(LED2_GPIO, &GPIO_InitStruct);
	GPIO_WriteBit(LED1_GPIO, LED1_PIN, Bit_SET);
	GPIO_WriteBit(LED2_GPIO, LED2_PIN, Bit_SET);
}

void LED_OnOff(uint16_t pin, bool state) {
	GPIO_WriteBit(GPIOA, pin, state ? Bit_RESET : Bit_SET);
}
