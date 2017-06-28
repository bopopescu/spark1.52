package org.apache.spark.examples.demoIBM

import scala.io.Source
import java.io.File
/**
 * ��ʽת��
 * Created by zhiwang on 2015/7/21.
 */

class RichFile(val file: File) {
  def read() = Source.fromFile(file.getPath()).mkString
}
object Context {
  //File -> RichFile,������implicit�ؼ���
  implicit def file2RichFile(file: File) = new RichFile(file)
}
/**
 �����ִ�й������£� 
1. ����File ��read ���� 
2. ������������File��û��read ����ʱ,����ֱ�ӱ���,����ִ�е����� 
3. ��鵱ǰ���������Ƿ��н���File�� implicit ����,��û��ֱ�ӱ���,����,ִ�е�4�� 
4. ��File��Ϊ����ʵ����RichFile,�ټ���Ƿ���read ����,��û��ֱ�ӱ��� 
5. ִ��read����
����ִ�й�����,��Ҫ�ر�ע�����, ������
**/
object ImplicitConvertion {
  def main(args: Array[String]) {
    //�ڵ�ǰ������������ʽת��
    import Context.file2RichFile
    //�ļ�ֻ��Ӣ��,���ܰ�������
    //File������û��read������,��Ҫ��ʽת��Ϊ�Զ����RichFile
    println(new File("c:\\aa.txt").read())
  }
}