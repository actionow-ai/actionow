"use client";

import { useState, useEffect, useCallback, useMemo, useRef } from "react";
import { useTranslations } from "next-intl";
import {
  Button,
  Modal,
  Skeleton,
  Card,
  Select,
  ListBox,
  Chip,
  Table,
  Pagination,
  Tooltip,
  Spinner,
  toast,
} from "@heroui/react";
import {
  Zap,
  ArrowUpRight,
  ArrowDownLeft,
  CreditCard,
  History,
  Snowflake,
  RotateCcw,
  Gift,
  ArrowLeftRight,
  TrendingUp,
  TrendingDown,
  Copy,
  Check,
  QrCode,
  CheckCircle2,
  XCircle,
  Loader2,
  RefreshCw,
} from "lucide-react";
import { QRCodeSVG } from "qrcode.react";
import { useSearchParams } from "next/navigation";
import { walletService, billingService, giftCodeService, getErrorFromException } from "@/lib/api";
import { useWorkspace } from "@/components/providers/workspace-provider";
import { useWalletUpdates } from "@/lib/websocket";
import { useWalletActions } from "@/lib/stores/wallet-store";
import type { WalletDTO, TransactionDTO, TransactionType, PaymentProvider, TopupRateConfig } from "@/lib/api/dto";
import type { WalletBalanceChangedData } from "@/lib/websocket/types";
import {
  FormField,
} from "@/components/director";
import { useLocale } from "next-intl";

// Transaction type configuration with icons
const transactionTypeConfig: Record<TransactionType, {
  label: string;
  icon: typeof Zap;
  color: "success" | "danger" | "warning" | "accent" | "default";
}> = {
  TOPUP:        { label: "充值",   icon: CreditCard,    color: "success" },
  CONSUME:      { label: "消费",   icon: ArrowUpRight,  color: "danger"  },
  REFUND:       { label: "退款",   icon: RotateCcw,     color: "success" },
  FREEZE:       { label: "冻结",   icon: Snowflake,     color: "warning" },
  UNFREEZE:     { label: "解冻",   icon: Snowflake,     color: "accent"  },
  GIFT:         { label: "赠送",   icon: Gift,          color: "success" },
  TRANSFER_IN:  { label: "转入",   icon: ArrowDownLeft, color: "success" },
  TRANSFER_OUT: { label: "转出",   icon: ArrowUpRight,  color: "danger"  },
};

const PAGE_SIZE_OPTIONS = [10, 20, 50] as const;
type PageSize = (typeof PAGE_SIZE_OPTIONS)[number];

// Format relative time
function formatRelativeTime(dateStr: string): string {
  const date = new Date(dateStr);
  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  const diffMinutes = Math.floor(diffMs / (1000 * 60));
  const diffHours = Math.floor(diffMs / (1000 * 60 * 60));
  const diffDays = Math.floor(diffMs / (1000 * 60 * 60 * 24));

  if (diffMinutes < 1) return "刚刚";
  if (diffMinutes < 60) return `${diffMinutes}分钟前`;
  if (diffHours < 24) return `${diffHours}小时前`;
  if (diffDays === 1) return "昨天";
  if (diffDays < 7) return `${diffDays}天前`;
  return date.toLocaleDateString("zh-CN", { month: "short", day: "numeric" });
}

// Description tooltip: truncated text with hover to see all + copy
function DescriptionTooltip({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);

  const handleCopy = async (e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      /* ignore */
    }
  };

  if (text.length <= 40) {
    return <span className="text-sm text-muted">{text}</span>;
  }

  return (
    <Tooltip delay={300}>
      <Tooltip.Trigger className="cursor-help text-left">
        <p className="max-w-[200px] truncate text-sm text-muted">{text}</p>
      </Tooltip.Trigger>
      <Tooltip.Content className="max-w-[320px] p-2">
        <p className="max-h-24 overflow-y-auto whitespace-pre-wrap break-all text-xs leading-relaxed">
          {text}
        </p>
        <button
          onClick={handleCopy}
          className="mt-1.5 flex items-center gap-1 text-[11px] text-foreground-3 transition-colors hover:text-foreground"
        >
          {copied ? <Check className="size-3 text-success" /> : <Copy className="size-3" />}
          {copied ? "已复制" : "复制"}
        </button>
      </Tooltip.Content>
    </Tooltip>
  );
}

export default function WalletPage() {
  const t = useTranslations("workspace.director.studioManagement");
  const { currentWorkspaceId } = useWorkspace();
  const { setBalance } = useWalletActions();
  const [wallet, setWallet] = useState<WalletDTO | null>(null);
  const [transactions, setTransactions] = useState<TransactionDTO[]>([]);
  const [isLoading, setIsLoading] = useState(true);

  const [currentPage, setCurrentPage] = useState(1);
  const [totalPages, setTotalPages] = useState(1);
  const [totalRecords, setTotalRecords] = useState(0);
  const [isLoadingTransactions, setIsLoadingTransactions] = useState(false);
  const [pageSize, setPageSize] = useState<PageSize>(10);

  const [filterType, setFilterType] = useState<TransactionType | "">("");

  // ── Payment topup states ──────────────────────────────────────────────────
  const locale = useLocale();
  const searchParams = useSearchParams();
  const [showTopupModal, setShowTopupModal] = useState(false);
  const [topupAmount, setTopupAmount] = useState("");
  const [topupDescription, setTopupDescription] = useState("");
  const [isTopuping, setIsTopuping] = useState(false);

  type TopupStep = "select" | "paying" | "success" | "failed";
  const [topupStep, setTopupStep] = useState<TopupStep>("select");
  const [selectedProvider, setSelectedProvider] = useState<PaymentProvider>("STRIPE");
  const [currentOrderNo, setCurrentOrderNo] = useState<string | null>(null);
  const [wechatQrUrl, setWechatQrUrl] = useState<string | null>(null);
  const [paidPoints, setPaidPoints] = useState<number>(0);
  const [paymentError, setPaymentError] = useState<string>("");
  const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const [topupRates, setTopupRates] = useState<Record<string, TopupRateConfig>>({});
  const [enabledProviders, setEnabledProviders] = useState<PaymentProvider[]>(["STRIPE", "WECHATPAY"]);

  // ── Gift code redeem ──────────────────────────────────────────────────────
  const [showRedeemModal, setShowRedeemModal] = useState(false);
  const [redeemCode, setRedeemCode] = useState("");
  const [isRedeeming, setIsRedeeming] = useState(false);
  const [redeemError, setRedeemError] = useState<string>("");
  const [redeemSuccessPoints, setRedeemSuccessPoints] = useState<number | null>(null);

  useEffect(() => {
    const loadData = async () => {
      if (!currentWorkspaceId) return;
      try {
        setIsLoading(true);
        const walletData = await walletService.getWallet();
        setWallet(walletData);
        setBalance(walletData.available, walletData.frozen);
      } catch (error) {
        console.error("Failed to load wallet data:", error);
        toast.danger(getErrorFromException(error, locale));
      } finally {
        setIsLoading(false);
      }
    };
    loadData();
  }, [currentWorkspaceId, setBalance]);

  // ── Stripe redirect: detect order_no param and verify payment ──────────
  const stripeVerifiedRef = useRef(false);
  useEffect(() => {
    const orderNo = searchParams.get("order_no");
    if (!orderNo || stripeVerifiedRef.current) return;
    stripeVerifiedRef.current = true;

    // Clean up URL immediately
    window.history.replaceState(null, "", `/${locale}/workspace/management/wallet`);

    let cancelled = false;
    const verifyWithRetry = async () => {
      const maxRetries = 5;
      for (let i = 0; i < maxRetries; i++) {
        if (cancelled) return;
        try {
          const order = await billingService.verifyPayment(orderNo);
          if (cancelled) return;
          if (order.status === "PAID") {
            toast.success(`支付成功，+${order.pointsAmount.toLocaleString()} 积分已到账`);
            // Refresh wallet
            const w = await walletService.getWallet();
            if (cancelled) return;
            setWallet(w);
            setBalance(w.available, w.frozen);
            const res = await walletService.getTransactions({ current: 1, size: pageSize });
            if (cancelled) return;
            setTransactions(res.records);
            setCurrentPage(1);
            setTotalPages(res.pages);
            setTotalRecords(res.total);
            return;
          }
          if (order.status === "FAILED" || order.status === "CANCELED" || order.status === "EXPIRED") {
            toast.danger(order.failMessage || "支付失败，请重试");
            return;
          }
        } catch {
          // Ignore, will retry
        }
        // Wait 2s before next retry
        if (i < maxRetries - 1) {
          await new Promise((r) => setTimeout(r, 2000));
        }
      }
      if (!cancelled) {
        toast.info("支付结果确认中，请稍后刷新页面查看");
      }
    };

    verifyWithRetry();
    return () => { cancelled = true; };
  }, [searchParams, locale, setBalance, pageSize]);

  // ── Real-time wallet updates via WebSocket ──────────────────────────────
  const handleWalletChanged = useCallback((data: WalletBalanceChangedData) => {
    setWallet((prev) => prev ? {
      ...prev,
      available: data.balance,
      balance: data.balance,
      frozen: data.frozen,
      totalBalance: data.balance + data.frozen,
    } : prev);

    if (currentPage === 1) {
      walletService.getTransactions({ current: 1, size: pageSize, type: filterType || undefined })
        .then((res) => {
          setTransactions(res.records);
          setTotalPages(res.pages);
          setTotalRecords(res.total);
        })
        .catch(() => {});
    }

    const abs = Math.abs(data.delta).toLocaleString();
    switch (data.transactionType) {
      case "TOPUP":    toast.success(`充值成功，+${abs} 积分`); break;
      case "CONSUME":  toast.info(`消费 ${abs} 积分`);          break;
      case "FREEZE":   toast.info(`已预扣 ${abs} 积分`);        break;
      case "UNFREEZE": toast.success(`已退回 ${abs} 积分`);     break;
    }
  }, [currentPage, filterType, pageSize]);

  useWalletUpdates(handleWalletChanged, [handleWalletChanged]);

  useEffect(() => {
    const loadTransactions = async () => {
      if (!currentWorkspaceId) return;
      try {
        setIsLoadingTransactions(true);
        const response = await walletService.getTransactions({
          current: currentPage,
          size: pageSize,
          type: filterType || undefined,
        });
        setTransactions(response.records);
        setTotalPages(response.pages);
        setTotalRecords(response.total);
      } catch (error) {
        console.error("Failed to load transactions:", error);
        toast.danger(getErrorFromException(error, locale));
      } finally {
        setIsLoadingTransactions(false);
      }
    };
    loadTransactions();
  }, [currentWorkspaceId, currentPage, filterType, pageSize]);

  // Stop order polling on unmount or modal close
  const stopPolling = useCallback(() => {
    if (pollingRef.current) {
      clearInterval(pollingRef.current);
      pollingRef.current = null;
    }
  }, []);

  useEffect(() => stopPolling, [stopPolling]);

  // Fetch exchange rate for a currency (cached in state)
  const fetchRate = useCallback(async (currency: string) => {
    if (topupRates[currency]) return;
    try {
      const rate = await billingService.getTopupRate(currency);
      setTopupRates((prev) => ({ ...prev, [currency]: rate }));
    } catch {
      // Fallback: will show nothing if rate unavailable
    }
  }, [topupRates]);

  // Fetch rates when modal opens or provider changes
  useEffect(() => {
    if (!showTopupModal) return;
    const currency = selectedProvider === "WECHATPAY" ? "CNY" : "USD";
    fetchRate(currency);
  }, [showTopupModal, selectedProvider, fetchRate]);

  // Fetch enabled payment providers when topup modal opens
  useEffect(() => {
    if (!showTopupModal) return;
    let cancelled = false;
    billingService
      .getEnabledPaymentProviders()
      .then((list) => {
        if (cancelled) return;
        const filtered = (list || []).filter((p): p is PaymentProvider => p === "STRIPE" || p === "WECHATPAY");
        if (filtered.length === 0) {
          setEnabledProviders(["STRIPE", "WECHATPAY"]);
          return;
        }
        setEnabledProviders(filtered);
        setSelectedProvider((prev) => (filtered.includes(prev) ? prev : filtered[0]));
      })
      .catch(() => { if (!cancelled) setEnabledProviders(["STRIPE", "WECHATPAY"]); });
    return () => { cancelled = true; };
  }, [showTopupModal]);

  // Compute points preview from rate
  const pointsPreview = useMemo(() => {
    const amount = parseFloat(topupAmount);
    if (!amount || amount <= 0) return null;
    const currency = selectedProvider === "WECHATPAY" ? "CNY" : "USD";
    const rate = topupRates[currency];
    if (!rate) return null;
    const amountMinor = Math.round(amount * rate.minorPerMajorUnit);
    return Math.floor((amountMinor * rate.pointsPerMajorUnit) / rate.minorPerMajorUnit);
  }, [topupAmount, selectedProvider, topupRates]);

  // Rate display string
  const rateDisplay = useMemo(() => {
    const currency = selectedProvider === "WECHATPAY" ? "CNY" : "USD";
    const rate = topupRates[currency];
    if (!rate) return null;
    const label = selectedProvider === "WECHATPAY" ? "元" : "USD";
    return `1 ${label} = ${rate.pointsPerMajorUnit} 积分`;
  }, [selectedProvider, topupRates]);

  const resetTopupModal = useCallback(() => {
    setTopupStep("select");
    setTopupAmount("");
    setTopupDescription("");
    setSelectedProvider("STRIPE");
    setCurrentOrderNo(null);
    setWechatQrUrl(null);
    setPaidPoints(0);
    setPaymentError("");
    setIsTopuping(false);
    stopPolling();
  }, [stopPolling]);

  // Poll order status (WeChat QR / Stripe waiting)
  const startOrderPolling = useCallback((orderNo: string) => {
    stopPolling();
    let attempts = 0;
    pollingRef.current = setInterval(async () => {
      attempts++;
      if (attempts > 120) {
        stopPolling();
        setPaymentError("支付超时，请检查支付状态或重试");
        setTopupStep("failed");
        return;
      }
      try {
        const order = await billingService.getOrder(orderNo);
        if (order.status === "PAID") {
          stopPolling();
          setPaidPoints(order.pointsAmount);
          setTopupStep("success");
          // Refresh wallet data
          walletService.getWallet().then((w) => {
            setWallet(w);
            setBalance(w.available, w.frozen);
          }).catch(() => {});
          walletService.getTransactions({ current: 1, size: pageSize }).then((res) => {
            setTransactions(res.records);
            setCurrentPage(1);
            setTotalPages(res.pages);
            setTotalRecords(res.total);
          }).catch(() => {});
        } else if (order.status === "FAILED" || order.status === "CANCELED" || order.status === "EXPIRED") {
          stopPolling();
          setPaymentError(order.failMessage || `支付${order.status === "EXPIRED" ? "已过期" : "失败"}，请重试`);
          setTopupStep("failed");
        }
      } catch {
        // Ignore polling errors, keep trying
      }
    }, 3000);
  }, [stopPolling, setBalance, pageSize]);

  const handleTopup = async () => {
    if (!currentWorkspaceId || !topupAmount) return;
    const amount = parseFloat(topupAmount);
    if (isNaN(amount) || amount <= 0) return;

    const currency = selectedProvider === "WECHATPAY" ? "CNY" : "USD";
    // Convert major unit to minor unit (cents/fen)
    const amountMinor = Math.round(amount * 100);

    try {
      setIsTopuping(true);

      // Step 1: Create order
      const order = await billingService.createTopupOrder({
        amountMinor,
        currency,
        provider: selectedProvider,
        description: topupDescription || undefined,
      });
      setCurrentOrderNo(order.orderNo);
      setPaidPoints(order.pointsAmount);

      // Step 2: Create checkout session
      const walletPath = `/${locale}/workspace/management/wallet`;
      const origin = window.location.origin;
      const session = await billingService.createCheckoutSession(order.orderNo, {
        successUrl: `${origin}${walletPath}?order_no=${order.orderNo}`,
        cancelUrl: `${origin}${walletPath}`,
      });

      if (selectedProvider === "STRIPE") {
        // Stripe: redirect to Stripe checkout in same tab
        // On completion, Stripe redirects back to successUrl with order_no
        window.location.href = session.checkoutUrl;
      } else {
        // WeChat: show QR code, poll for status
        setWechatQrUrl(session.checkoutUrl);
        setTopupStep("paying");
        startOrderPolling(order.orderNo);
      }
    } catch (error) {
      setPaymentError(getErrorFromException(error, locale));
      setTopupStep("failed");
    } finally {
      setIsTopuping(false);
    }
  };

  const resetRedeemModal = useCallback(() => {
    setRedeemCode("");
    setRedeemError("");
    setRedeemSuccessPoints(null);
    setIsRedeeming(false);
  }, []);

  const handleRedeem = async () => {
    const code = redeemCode.trim();
    if (!code) {
      setRedeemError("请输入礼包码");
      return;
    }
    setRedeemError("");
    try {
      setIsRedeeming(true);
      const res = await giftCodeService.redeem({ code });
      setRedeemSuccessPoints(res.points);
      // Refresh wallet & transactions
      try {
        const w = await walletService.getWallet();
        setWallet(w);
        setBalance(w.available, w.frozen);
      } catch { /* ignore */ }
      try {
        const list = await walletService.getTransactions({
          current: 1,
          size: pageSize,
          type: filterType || undefined,
        });
        setTransactions(list.records);
        setCurrentPage(1);
        setTotalPages(list.pages);
        setTotalRecords(list.total);
      } catch { /* ignore */ }
      toast.success(`兑换成功，+${res.points.toLocaleString()} 积分`);
    } catch (error) {
      setRedeemError(getErrorFromException(error, locale));
    } finally {
      setIsRedeeming(false);
    }
  };

  const formatAmount = (amount: number) => Math.abs(amount).toLocaleString();

  const handlePageChange = (page: number) => {
    if (page >= 1 && page <= totalPages) setCurrentPage(page);
  };

  const handleFilterChange = (type: TransactionType | "") => {
    setFilterType(type);
    setCurrentPage(1);
  };

  const handlePageSizeChange = (size: PageSize) => {
    setPageSize(size);
    setCurrentPage(1);
  };

  // Pagination numbers with ellipsis
  const pageNumbers = useMemo((): (number | "ellipsis")[] => {
    if (totalPages <= 7) return Array.from({ length: totalPages }, (_, i) => i + 1);
    const pages: (number | "ellipsis")[] = [1];
    if (currentPage > 3) pages.push("ellipsis");
    const start = Math.max(2, currentPage - 1);
    const end = Math.min(totalPages - 1, currentPage + 1);
    for (let i = start; i <= end; i++) pages.push(i);
    if (currentPage < totalPages - 2) pages.push("ellipsis");
    pages.push(totalPages);
    return pages;
  }, [currentPage, totalPages]);

  const startItem = totalRecords === 0 ? 0 : (currentPage - 1) * pageSize + 1;
  const endItem = Math.min(currentPage * pageSize, totalRecords);

  const quickAmountsStripe = [1, 5, 10, 50];
  const quickAmountsWechat = [10, 50, 100, 500];
  const quickAmounts = selectedProvider === "WECHATPAY" ? quickAmountsWechat : quickAmountsStripe;
  const currencySymbol = selectedProvider === "WECHATPAY" ? "¥" : "$";

  if (isLoading) {
    return (
      <div className="flex h-full flex-col">
        <div className="flex shrink-0 items-center justify-between gap-3 pb-4">
          <Skeleton className="h-8 w-48 rounded-md" />
          <div className="flex gap-2">
            <Skeleton className="h-8 w-32 rounded-md" />
            <Skeleton className="h-8 w-24 rounded-md" />
          </div>
        </div>
        <div className="min-h-0 flex-1 space-y-4 overflow-auto">
          <Skeleton className="h-96 w-full rounded-xl" />
        </div>
      </div>
    );
  }

  return (
    <>
      <div className="flex h-full flex-col">
        {/* Toolbar */}
        <div className="flex shrink-0 items-center justify-between gap-3 pb-4">
          {/* Left: filter + stats */}
          <div className="flex items-center gap-2">
            <Select
              aria-label="交易类型筛选"
              className="w-32"
              placeholder="全部类型"
              variant="secondary"
              value={filterType || null}
              onChange={(value) => handleFilterChange((value as TransactionType) || "")}
            >
              <Select.Trigger className="text-xs">
                <Select.Value />
                <Select.Indicator />
              </Select.Trigger>
              <Select.Popover>
                <ListBox>
                  <ListBox.Item id="" textValue="全部类型">
                    全部类型
                    <ListBox.ItemIndicator />
                  </ListBox.Item>
                  {Object.entries(transactionTypeConfig).map(([type, config]) => (
                    <ListBox.Item key={type} id={type} textValue={config.label}>
                      {config.label}
                      <ListBox.ItemIndicator />
                    </ListBox.Item>
                  ))}
                </ListBox>
              </Select.Popover>
            </Select>
            <span className="text-xs text-muted">共 {totalRecords} 条</span>
          </div>

          {/* Right: Actions */}
          <div className="flex items-center gap-2">
            <Button size="sm" onPress={() => setShowTopupModal(true)}>
              <CreditCard className="size-4" />
              充值
            </Button>
            <Button variant="secondary" size="sm" onPress={() => setShowRedeemModal(true)}>
              <Gift className="size-4" />
              礼包码兑换
            </Button>
            <Button variant="secondary" size="sm" isDisabled>
              <ArrowLeftRight className="size-4" />
              转账
              <Chip size="sm" variant="soft" className="ml-1">即将上线</Chip>
            </Button>
            <Button
              variant="ghost"
              size="sm"
              isIconOnly
              onPress={() => {
                walletService.getWallet().then((w) => {
                  setWallet(w);
                  setBalance(w.available, w.frozen);
                });
              }}
            >
              <RefreshCw className="size-4" />
            </Button>
          </div>
        </div>

        {/* Content: scrollable area */}
        <div className="min-h-0 flex-1 overflow-auto">
          <div className="grid gap-4" style={{ gridTemplateColumns: "1fr 280px" }}>
            {/* Left: Transaction Table */}
            <div className="space-y-4">
            <Table>
              <Table.ScrollContainer className="max-h-[60vh] overflow-y-auto">
                <Table.Content
                  aria-label="交易记录"
                  className="min-w-[600px]"
                >
                  <Table.Header className="sticky top-0 z-10">
                    <Table.Column isRowHeader className="w-36">类型</Table.Column>
                    <Table.Column>描述 / 余额变化</Table.Column>
                    <Table.Column className="w-32 text-right">变动</Table.Column>
                    <Table.Column className="w-32 text-right">余额</Table.Column>
                    <Table.Column className="w-28 text-right">时间</Table.Column>
                  </Table.Header>
                  <Table.Body
                    renderEmptyState={() =>
                      isLoadingTransactions ? (
                        <div className="flex items-center justify-center py-20">
                          <div className="size-6 animate-spin rounded-full border-2 border-accent border-t-transparent" />
                        </div>
                      ) : (
                        <div className="flex flex-col items-center justify-center py-20 text-center">
                          <History className="size-10 text-muted/30" />
                          <p className="mt-3 text-sm text-muted">暂无交易记录</p>
                        </div>
                      )
                    }
                  >
                    <Table.Collection items={transactions}>
                      {(transaction) => {
                        const config = transactionTypeConfig[transaction.transactionType] || {
                          label: transaction.transactionType,
                          icon: Zap,
                          color: "default" as const,
                        };
                        const Icon = config.icon;
                        const isPositive = transaction.amount > 0;

                        return (
                          <Table.Row id={transaction.id}>
                            {/* Type */}
                            <Table.Cell>
                              <div className="flex items-center gap-2">
                                <div className={`flex size-7 shrink-0 items-center justify-center rounded-lg ${
                                  config.color === "success" ? "bg-success/10" :
                                  config.color === "danger"  ? "bg-danger/10"  :
                                  config.color === "warning" ? "bg-warning/10" :
                                  config.color === "accent"  ? "bg-accent/10"  :
                                  "bg-muted/10"
                                }`}>
                                  <Icon className={`size-3.5 ${
                                    config.color === "success" ? "text-success" :
                                    config.color === "danger"  ? "text-danger"  :
                                    config.color === "warning" ? "text-warning" :
                                    config.color === "accent"  ? "text-accent"  :
                                    "text-muted"
                                  }`} />
                                </div>
                                <Chip size="sm" variant="soft" color={config.color}>
                                  {config.label}
                                </Chip>
                              </div>
                            </Table.Cell>

                            {/* Description */}
                            <Table.Cell>
                              {transaction.description ? (
                                <DescriptionTooltip text={transaction.description} />
                              ) : (
                                <span className="text-xs tabular-nums text-muted">
                                  {formatAmount(transaction.balanceBefore)} → {formatAmount(transaction.balanceAfter)}
                                </span>
                              )}
                            </Table.Cell>

                            {/* Amount */}
                            <Table.Cell className="text-right">
                              <span className={`font-semibold tabular-nums ${isPositive ? "text-success" : "text-danger"}`}>
                                {isPositive ? "+" : ""}{formatAmount(transaction.amount)}
                              </span>
                            </Table.Cell>

                            {/* Balance after */}
                            <Table.Cell className="text-right">
                              <span className="tabular-nums text-sm text-foreground">
                                {formatAmount(transaction.balanceAfter)}
                              </span>
                            </Table.Cell>

                            {/* Time */}
                            <Table.Cell className="text-right">
                              <span className="text-xs text-muted">
                                {formatRelativeTime(transaction.createdAt)}
                              </span>
                            </Table.Cell>
                          </Table.Row>
                        );
                      }}
                    </Table.Collection>
                  </Table.Body>
                </Table.Content>
              </Table.ScrollContainer>

              <Table.Footer>
                <Pagination size="sm">
                  <Pagination.Summary>
                    <div className="flex items-center gap-2.5">
                      {totalRecords > 0 && (
                        <span className="tabular-nums text-xs text-muted">
                          {startItem}–{endItem} / {totalRecords}
                        </span>
                      )}
                      <Select
                        aria-label="每页条数"
                        variant="secondary"
                        value={String(pageSize)}
                        onChange={(value) => handlePageSizeChange(Number(value) as PageSize)}
                      >
                        <Select.Trigger className="h-7 w-24 text-xs">
                          <Select.Value />
                          <Select.Indicator />
                        </Select.Trigger>
                        <Select.Popover>
                          <ListBox>
                            {PAGE_SIZE_OPTIONS.map((s) => (
                              <ListBox.Item key={s} id={String(s)} textValue={`${s} 条/页`}>
                                {s} 条/页
                                <ListBox.ItemIndicator />
                              </ListBox.Item>
                            ))}
                          </ListBox>
                        </Select.Popover>
                      </Select>
                    </div>
                  </Pagination.Summary>
                  {totalPages > 1 && (
                    <Pagination.Content>
                      <Pagination.Item>
                        <Pagination.Previous
                          isDisabled={currentPage === 1}
                          onPress={() => handlePageChange(currentPage - 1)}
                        >
                          <Pagination.PreviousIcon />
                        </Pagination.Previous>
                      </Pagination.Item>
                      {pageNumbers.map((p, i) =>
                        p === "ellipsis" ? (
                          <Pagination.Item key={`e-${i}`}>
                            <Pagination.Ellipsis />
                          </Pagination.Item>
                        ) : (
                          <Pagination.Item key={p}>
                            <Pagination.Link
                              isActive={p === currentPage}
                              onPress={() => handlePageChange(p as number)}
                            >
                              {p}
                            </Pagination.Link>
                          </Pagination.Item>
                        )
                      )}
                      <Pagination.Item>
                        <Pagination.Next
                          isDisabled={currentPage >= totalPages}
                          onPress={() => handlePageChange(currentPage + 1)}
                        >
                          <Pagination.NextIcon />
                        </Pagination.Next>
                      </Pagination.Item>
                    </Pagination.Content>
                  )}
                </Pagination>
              </Table.Footer>
            </Table>
            </div>

            {/* Right: Balance & Stats */}
            <div className="space-y-4">
              {/* Balance Card */}
              <Card className="w-full" variant="secondary">
                <Card.Content className="p-3">
                  <div className="flex items-center justify-between">
                    <span className="text-sm text-muted">可用余额</span>
                    <div className="flex size-7 items-center justify-center rounded-lg bg-accent/10">
                      <Zap className="size-3.5 text-accent" />
                    </div>
                  </div>
                  <p className="mt-1.5 text-2xl font-bold text-foreground">
                    {formatAmount(wallet?.available || 0)}
                  </p>
                  <p className="mt-1 text-xs text-muted">积分</p>
                </Card.Content>
              </Card>

              {/* Frozen Card */}
              <Card className="w-full" variant="secondary">
                <Card.Content className="p-3">
                  <div className="flex items-center justify-between">
                    <span className="text-sm text-muted">冻结中</span>
                    <div className="flex size-7 items-center justify-center rounded-lg bg-warning/10">
                      <Snowflake className="size-3.5 text-warning" />
                    </div>
                  </div>
                  <p className="mt-1.5 text-2xl font-bold text-foreground">
                    {formatAmount(wallet?.frozen || 0)}
                  </p>
                  <p className="mt-1 text-xs text-muted">等待消费确认</p>
                </Card.Content>
              </Card>

              {/* Stats Card */}
              <Card className="w-full" variant="secondary">
                <Card.Content className="p-3">
                  <div className="flex items-center justify-between">
                    <span className="text-sm text-muted">收支统计</span>
                    <div className="flex size-7 items-center justify-center rounded-lg bg-muted/10">
                      <History className="size-3.5 text-muted" />
                    </div>
                  </div>
                  <div className="mt-3 space-y-2">
                    <div className="flex items-center justify-between rounded-md bg-surface-secondary px-3 py-2 text-xs">
                      <span className="flex items-center gap-1.5 text-muted">
                        <TrendingUp className="size-3 text-success" />
                        累计充值
                      </span>
                      <span className="font-medium text-success">+{formatAmount(wallet?.totalRecharged || 0)}</span>
                    </div>
                    <div className="flex items-center justify-between rounded-md bg-surface-secondary px-3 py-2 text-xs">
                      <span className="flex items-center gap-1.5 text-muted">
                        <TrendingDown className="size-3 text-danger" />
                        累计消费
                      </span>
                      <span className="font-medium text-danger">-{formatAmount(wallet?.totalConsumed || 0)}</span>
                    </div>
                  </div>
                </Card.Content>
              </Card>
            </div>
          </div>
        </div>
      </div>

      {/* Topup Payment Modal */}
      <Modal.Backdrop
        isOpen={showTopupModal}
        onOpenChange={(open) => {
          if (!open) { stopPolling(); resetTopupModal(); }
          setShowTopupModal(open);
        }}
      >
        <Modal.Container size="md">
          <Modal.Dialog>
            <Modal.CloseTrigger />
            <Modal.Header>
              <Modal.Heading>
                {topupStep === "select" && "账户充值"}
                {topupStep === "paying" && "微信扫码支付"}
                {topupStep === "success" && "充值成功"}
                {topupStep === "failed" && "充值失败"}
              </Modal.Heading>
            </Modal.Header>
            <Modal.Body className="space-y-4">
              {/* ── Step: select provider + amount ── */}
              {topupStep === "select" && (
                <>
                  {/* Payment channel selection */}
                  {enabledProviders.length === 0 ? (
                    <div className="rounded-lg border border-dashed border-border bg-surface p-4 text-center text-sm text-muted">
                      管理员暂未启用任何支付渠道
                    </div>
                  ) : (
                    <div>
                      <label className="mb-2 block text-sm font-medium text-foreground">选择支付方式</label>
                      <div
                        className="grid gap-3"
                        style={{ gridTemplateColumns: `repeat(${enabledProviders.length}, minmax(0, 1fr))` }}
                      >
                        {enabledProviders.includes("STRIPE") && (
                          <button
                            onClick={() => { setSelectedProvider("STRIPE"); setTopupAmount(""); }}
                            className={`flex flex-col items-center gap-2 rounded-xl border-2 p-4 transition-all ${
                              selectedProvider === "STRIPE"
                                ? "border-accent bg-accent/5 shadow-sm"
                                : "border-border hover:border-accent/40"
                            }`}
                          >
                            <CreditCard className={`size-6 ${selectedProvider === "STRIPE" ? "text-accent" : "text-muted"}`} />
                            <span className={`text-sm font-medium ${selectedProvider === "STRIPE" ? "text-accent" : "text-foreground"}`}>
                              Stripe
                            </span>
                            <span className="text-xs text-muted">信用卡 / USD</span>
                          </button>
                        )}
                        {enabledProviders.includes("WECHATPAY") && (
                          <button
                            onClick={() => { setSelectedProvider("WECHATPAY"); setTopupAmount(""); }}
                            className={`flex flex-col items-center gap-2 rounded-xl border-2 p-4 transition-all ${
                              selectedProvider === "WECHATPAY"
                                ? "border-green-500 bg-green-500/5 shadow-sm"
                                : "border-border hover:border-green-500/40"
                            }`}
                          >
                            <QrCode className={`size-6 ${selectedProvider === "WECHATPAY" ? "text-green-500" : "text-muted"}`} />
                            <span className={`text-sm font-medium ${selectedProvider === "WECHATPAY" ? "text-green-600" : "text-foreground"}`}>
                              微信支付
                            </span>
                            <span className="text-xs text-muted">扫码支付 / CNY</span>
                          </button>
                        )}
                      </div>
                    </div>
                  )}

                  {/* Amount input */}
                  <div>
                    <label className="mb-1.5 block text-sm font-medium text-foreground">充值金额</label>
                    <div className="relative">
                      <div className="absolute left-3 top-1/2 flex -translate-y-1/2 items-center gap-1">
                        <span className="text-sm font-semibold text-muted">{currencySymbol}</span>
                      </div>
                      <input
                        type="number"
                        value={topupAmount}
                        onChange={(e) => setTopupAmount(e.target.value)}
                        placeholder={`输入金额 (${selectedProvider === "WECHATPAY" ? "元" : "USD"})`}
                        min="0.01"
                        step="0.01"
                        className="w-full rounded-lg border border-border bg-surface py-3 pl-10 pr-4 text-lg font-semibold text-foreground placeholder:text-muted focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                      />
                    </div>
                    <div className="mt-3 flex gap-2">
                      {quickAmounts.map((amount) => (
                        <button
                          key={amount}
                          onClick={() => setTopupAmount(amount.toString())}
                          className={`flex-1 rounded-lg border px-3 py-2 text-sm font-medium transition-colors ${
                            topupAmount === amount.toString()
                              ? "border-accent bg-accent/10 text-accent"
                              : "border-border text-muted hover:border-accent/50"
                          }`}
                        >
                          {currencySymbol}{amount}
                        </button>
                      ))}
                    </div>
                  </div>

                  {/* Points preview */}
                  {pointsPreview !== null && (
                    <div className="flex items-center gap-2 rounded-lg bg-accent/5 px-4 py-3">
                      <Zap className="size-4 text-accent" />
                      <span className="text-sm text-foreground">
                        预计获得 <span className="font-bold text-accent">{pointsPreview.toLocaleString()}</span> 积分
                      </span>
                      {rateDisplay && <span className="text-xs text-muted">({rateDisplay})</span>}
                    </div>
                  )}

                  <FormField
                    label="备注（可选）"
                    value={topupDescription}
                    onChange={setTopupDescription}
                    placeholder="充值备注"
                  />

                  <div className="rounded-lg bg-surface-secondary p-3">
                    <p className="text-sm text-muted">
                      {selectedProvider === "STRIPE"
                        ? "点击支付后将跳转至 Stripe 安全收银台完成付款，支付成功后积分自动到账。"
                        : "点击支付后将生成微信支付二维码，使用微信扫码完成付款，支付成功后积分自动到账。"
                      }
                    </p>
                  </div>
                </>
              )}

              {/* ── Step: paying (WeChat QR scan) ── */}
              {topupStep === "paying" && (
                <div className="flex flex-col items-center gap-4 py-4">
                  {wechatQrUrl && (
                    <div className="rounded-2xl border border-border bg-white p-4">
                      <QRCodeSVG value={wechatQrUrl} size={200} />
                    </div>
                  )}
                  <p className="text-sm text-foreground">请使用微信扫描二维码完成支付</p>
                  <div className="flex items-center gap-2 text-muted">
                    <Loader2 className="size-4 animate-spin" />
                    <span className="text-sm">等待支付结果...</span>
                  </div>
                  {topupAmount && (
                    <p className="text-xs text-muted">
                      订单金额: {currencySymbol}{topupAmount} | 订单号: {currentOrderNo}
                    </p>
                  )}
                </div>
              )}

              {/* ── Step: success ── */}
              {topupStep === "success" && (
                <div className="flex flex-col items-center gap-4 py-6">
                  <div className="flex size-16 items-center justify-center rounded-full bg-success/10">
                    <CheckCircle2 className="size-8 text-success" />
                  </div>
                  <p className="text-lg font-semibold text-foreground">支付成功</p>
                  <div className="flex items-center gap-2">
                    <Zap className="size-5 text-accent" />
                    <span className="text-2xl font-bold text-accent">+{paidPoints.toLocaleString()}</span>
                    <span className="text-sm text-muted">积分已到账</span>
                  </div>
                </div>
              )}

              {/* ── Step: failed ── */}
              {topupStep === "failed" && (
                <div className="flex flex-col items-center gap-4 py-6">
                  <div className="flex size-16 items-center justify-center rounded-full bg-danger/10">
                    <XCircle className="size-8 text-danger" />
                  </div>
                  <p className="text-lg font-semibold text-foreground">支付失败</p>
                  <p className="text-sm text-muted">{paymentError || "请稍后重试"}</p>
                </div>
              )}
            </Modal.Body>
            <Modal.Footer>
              {topupStep === "select" && (
                <>
                  <Button variant="secondary" onPress={() => { resetTopupModal(); setShowTopupModal(false); }}>
                    取消
                  </Button>
                  <Button
                    onPress={handleTopup}
                    isDisabled={!topupAmount || parseFloat(topupAmount) <= 0 || enabledProviders.length === 0}
                    isPending={isTopuping}
                  >
                    {({isPending}) => (<>{isPending ? <Spinner color="current" size="sm" /> : null}{selectedProvider === "WECHATPAY" ? "生成支付二维码" : "前往 Stripe 支付"}{topupAmount && parseFloat(topupAmount) > 0 && ` ${currencySymbol}${topupAmount}`}</>)}
                  </Button>
                </>
              )}
              {topupStep === "paying" && (
                <Button variant="secondary" onPress={() => { stopPolling(); resetTopupModal(); setShowTopupModal(false); }}>
                  取消支付
                </Button>
              )}
              {topupStep === "success" && (
                <Button onPress={() => { resetTopupModal(); setShowTopupModal(false); }}>
                  完成
                </Button>
              )}
              {topupStep === "failed" && (
                <>
                  <Button variant="secondary" onPress={() => { resetTopupModal(); setShowTopupModal(false); }}>
                    关闭
                  </Button>
                  <Button onPress={() => { setPaymentError(""); setTopupStep("select"); }}>
                    重试
                  </Button>
                </>
              )}
            </Modal.Footer>
          </Modal.Dialog>
        </Modal.Container>
      </Modal.Backdrop>

      {/* Gift code redemption modal */}
      <Modal.Backdrop
        isOpen={showRedeemModal}
        onOpenChange={(open) => {
          if (!open) resetRedeemModal();
          setShowRedeemModal(open);
        }}
      >
        <Modal.Container size="sm">
          <Modal.Dialog>
            <Modal.CloseTrigger />
            <Modal.Header>
              <Modal.Heading>
                {redeemSuccessPoints !== null ? "兑换成功" : "礼包码兑换"}
              </Modal.Heading>
            </Modal.Header>
            <Modal.Body className="space-y-4">
              {redeemSuccessPoints !== null ? (
                <div className="flex flex-col items-center gap-4 py-4">
                  <div className="flex size-16 items-center justify-center rounded-full bg-success/10">
                    <Gift className="size-8 text-success" />
                  </div>
                  <div className="flex items-center gap-2">
                    <Zap className="size-5 text-accent" />
                    <span className="text-2xl font-bold text-accent">
                      +{redeemSuccessPoints.toLocaleString()}
                    </span>
                    <span className="text-sm text-muted">积分已到账</span>
                  </div>
                </div>
              ) : (
                <>
                  <div>
                    <label className="mb-1.5 block text-sm font-medium text-foreground">
                      礼包码
                    </label>
                    <input
                      type="text"
                      value={redeemCode}
                      onChange={(e) => {
                        setRedeemCode(e.target.value.toUpperCase());
                        if (redeemError) setRedeemError("");
                      }}
                      onKeyDown={(e) => {
                        if (e.key === "Enter" && redeemCode.trim() && !isRedeeming) {
                          handleRedeem();
                        }
                      }}
                      placeholder="请输入礼包码"
                      autoFocus
                      className="w-full rounded-lg border border-border bg-surface px-4 py-3 font-mono text-base font-semibold tracking-wider text-foreground placeholder:text-muted focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                    />
                    {redeemError && (
                      <p className="mt-2 text-xs text-danger">{redeemError}</p>
                    )}
                  </div>
                  <div className="rounded-lg bg-surface-secondary p-3">
                    <p className="text-xs text-muted">
                      兑换的积分将进入当前工作空间钱包，每个礼包码每位用户仅可兑换一次。
                    </p>
                  </div>
                </>
              )}
            </Modal.Body>
            <Modal.Footer>
              {redeemSuccessPoints !== null ? (
                <Button
                  onPress={() => {
                    resetRedeemModal();
                    setShowRedeemModal(false);
                  }}
                >
                  完成
                </Button>
              ) : (
                <>
                  <Button
                    variant="secondary"
                    onPress={() => {
                      resetRedeemModal();
                      setShowRedeemModal(false);
                    }}
                  >
                    取消
                  </Button>
                  <Button
                    onPress={handleRedeem}
                    isPending={isRedeeming}
                    isDisabled={!redeemCode.trim()}
                  >
                    {({ isPending }) => (
                      <>
                        {isPending ? <Spinner color="current" size="sm" /> : null}
                        立即兑换
                      </>
                    )}
                  </Button>
                </>
              )}
            </Modal.Footer>
          </Modal.Dialog>
        </Modal.Container>
      </Modal.Backdrop>
    </>
  );
}
