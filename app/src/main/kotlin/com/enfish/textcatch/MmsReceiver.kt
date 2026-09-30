package com.enfish.textcatch

/**
 * MMS(WAP Push) 전용 수신기.
 * 로직은 SMSReceiver 와 동일하므로 상속만 해서 매니페스트에 별도 클래스로 등록한다.
 * (하나의 Receiver 클래스를 SMS/WAP_PUSH 두 곳에 중복 선언할 수 없기 때문)
 */
class MmsReceiver : SMSReceiver()
