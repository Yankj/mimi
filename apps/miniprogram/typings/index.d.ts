/** Minimal WeChat typings so Linux tsc can check the skeleton without the official typings package. */

declare namespace WechatMiniprogram {
  type AnyRecord = Record<string, unknown>;

  interface NavigateOptions {
    url: string;
    success?: () => void;
    fail?: (error: { errMsg: string }) => void;
    complete?: () => void;
  }

  interface ToastOptions {
    title: string;
    icon?: "success" | "error" | "loading" | "none";
    duration?: number;
  }

  interface ModalOptions {
    title?: string;
    content: string;
    showCancel?: boolean;
    confirmText?: string;
    cancelText?: string;
    success?: (result: { confirm: boolean; cancel: boolean }) => void;
  }

  interface Wx {
    reLaunch(options: NavigateOptions): void;
    switchTab(options: NavigateOptions): void;
    navigateTo(options: NavigateOptions): void;
    redirectTo(options: NavigateOptions): void;
    navigateBack(options?: { delta?: number }): void;
    showToast(options: ToastOptions): void;
    showModal(options: ModalOptions): void;
    setStorageSync(key: string, data: unknown): void;
    getStorageSync(key: string): unknown;
    removeStorageSync(key: string): void;
    clearStorageSync(): void;
  }

  namespace Page {
    type Instance<TData, TCustom> = TCustom & {
      data: TData;
      setData(data: Partial<TData> & AnyRecord, callback?: () => void): void;
    };
    type Options<TData, TCustom> = TCustom & {
      data?: TData;
      onLoad?(query: Record<string, string | undefined>): void;
      onShow?(): void;
      onHide?(): void;
      onUnload?(): void;
    } & ThisType<Instance<TData, TCustom>>;
  }

  namespace App {
    type Options<TCustom> = Partial<TCustom> & {
      onLaunch?(this: TCustom): void;
      onShow?(this: TCustom): void;
      onHide?(this: TCustom): void;
    };
  }

  namespace Component {
    type Instance<TData, TCustom> = TCustom & {
      data: TData;
      setData(data: Partial<TData> & AnyRecord, callback?: () => void): void;
      triggerEvent(name: string, detail?: unknown): void;
    };
    type Options<TData, TCustom> = {
      data?: TData;
      methods?: TCustom & ThisType<Instance<TData, TCustom>>;
    };
  }
}

declare const wx: WechatMiniprogram.Wx;

declare function App<TCustom>(options: WechatMiniprogram.App.Options<TCustom>): void;
declare function Page<TData extends WechatMiniprogram.AnyRecord, TCustom = WechatMiniprogram.AnyRecord>(
  options: WechatMiniprogram.Page.Options<TData, TCustom>,
): void;
declare function Component<TData extends WechatMiniprogram.AnyRecord, TCustom = WechatMiniprogram.AnyRecord>(
  options: WechatMiniprogram.Component.Options<TData, TCustom>,
): void;
declare function getApp<T = WechatMiniprogram.AnyRecord>(): T;
declare function getCurrentPages(): Array<{ route: string; setData(data: WechatMiniprogram.AnyRecord): void }>;
