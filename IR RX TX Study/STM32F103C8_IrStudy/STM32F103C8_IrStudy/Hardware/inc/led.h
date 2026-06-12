#ifndef __LED_H
#define __LED_H
#include "type.h"
#define LED1_GPIO	GPIOA
#define LED1_PIN	GPIO_Pin_0
#define LED2_GPIO	GPIOA
#define LED2_PIN	GPIO_Pin_1
void LED_Init(void);
void LED_OnOff(uint16_t pin, bool state);
#endif
